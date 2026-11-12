package com.kei.pulse.data

import com.kei.pulse.model.ClockOwner
import com.kei.pulse.model.ClockOwnership
import com.kei.pulse.model.CpuPolicyInfo
import com.kei.pulse.model.CustomTuning
import com.kei.pulse.model.PowerTargetMath
import com.kei.pulse.model.PowerTier
import com.kei.pulse.model.SideControlState
import com.kei.pulse.model.TierTransition
import com.kei.pulse.root.PrivilegedShell
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/** Writes a CPU/GPU cap map to the device, persisting it as the last-applied (and optionally Custom) values. */
interface CapWriter {
    val isAvailable: Boolean
    fun policies(): List<CpuPolicyInfo>
    suspend fun applyCaps(
        policies: List<CpuPolicyInfo>,
        values: Map<Int, Int>,
        isReset: Boolean,
        persistAsCustom: Boolean,
    ): Result<PerformanceRepository.ApplyOutcome>
}

sealed interface SideControlEdit {
    data class PowerTarget(val enabled: Boolean, val percent: Int) : SideControlEdit
    data class PowerTargetCpuOnly(val cpuOnly: Boolean) : SideControlEdit
    data class GpuLock(val locked: Boolean) : SideControlEdit
    data class GpuFloor(val percent: Int) : SideControlEdit
    data class CpuFloor(val percent: Int) : SideControlEdit
    data class PrimeBoost(val limited: Boolean) : SideControlEdit
    data class Governor(val option: GovernorOption) : SideControlEdit
}

sealed interface TuningOutcome {
    /**
     * The device was written. [caps] is the last cap apply's readback (null when the change wrote no caps);
     * [gpuLockLevel] is the pinned GPU power level after a lock; [governor] is the kernel governor now set.
     */
    data class Applied(
        val caps: PerformanceRepository.ApplyOutcome? = null,
        val gpuLockLevel: Int? = null,
        val governor: String? = null,
    ) : TuningOutcome

    /** Saved, but not written: a bound game or AutoTDP owns the clocks. */
    data object Deferred : TuningOutcome

    data class Failed(val reason: FailureReason, val message: String? = null) : TuningOutcome
}

enum class FailureReason { PSERVER_UNAVAILABLE, NO_POLICIES, NO_SAVED_CUSTOM, NO_GPU, NO_PRIME, WRITE_FAILED }

/**
 * The device-wide tuning mode: switching Tier and editing the side controls. Every entry point (app, tile,
 * Quick Access) goes through here, so a Tier switch means the same thing everywhere: caps, side controls,
 * floors, governor, the persisted state and the Custom snapshot, deferring to whoever owns the clocks.
 *
 * Lock order: this module's mutex is taken before [ClockOwnership]'s; never call in while holding that one.
 */
class GlobalTuning(
    private val store: TuningStore,
    private val caps: CapWriter,
    private val shell: PrivilegedShell,
    private val ownership: () -> ClockOwnership,
    private val mutex: Mutex = Mutex(),
) {
    private val governors = GovernorController(shell)
    private val cpuFloor = CpuFloorController(shell)
    private val gpuFloor = GpuFloorController(shell)

    suspend fun switchTo(tier: PowerTier): TuningOutcome = mutex.withLock {
        if (!caps.isAvailable) return TuningOutcome.Failed(FailureReason.PSERVER_UNAVAILABLE)
        val policies = caps.policies()
        if (policies.isEmpty()) return TuningOutcome.Failed(FailureReason.NO_POLICIES)
        val snapshot = store.customSnapshot()
        val next = TuningState(
            tier = tier,
            sideControls = if (tier == PowerTier.CUSTOM) {
                TierTransition.afterCustomRestore(snapshot)
            } else {
                TierTransition.afterPreset(store.state().sideControls)
            },
        )
        val owner = ownership()
        when (owner.current()) {
            is ClockOwner.BoundGame -> {
                persist(next)
                return TuningOutcome.Deferred
            }
            ClockOwner.GlobalAutoTdp -> owner.releaseGlobalAutoTdp()
            ClockOwner.Free -> Unit
        }
        val outcome = if (tier == PowerTier.CUSTOM) restoreCustom(policies, snapshot) else applyPreset(policies, tier)
        val presetFailed = outcome is TuningOutcome.Failed && tier != PowerTier.CUSTOM
        if (!presetFailed) persist(next)
        owner.capsWritten()
        outcome
    }

    /** Re-apply the active Tier (e.g. after AutoTDP hands the clocks back). */
    suspend fun reapply(): TuningOutcome = switchTo(store.state().tier)

    suspend fun edit(edit: SideControlEdit): TuningOutcome = mutex.withLock {
        val policies = caps.policies()
        if (policies.isEmpty()) return TuningOutcome.Failed(FailureReason.NO_POLICIES)
        val current = store.state()
        val side = current.sideControls
        val gpu = policies.firstOrNull { it.isGpu }
        val prime = primeOf(policies)
        val nextSide = when (edit) {
            is SideControlEdit.PowerTarget ->
                TierTransition.afterPowerTargetEnabled(side, edit.enabled).copy(powerTargetPercent = edit.percent)
            is SideControlEdit.PowerTargetCpuOnly -> side.copy(powerTargetCpuOnly = edit.cpuOnly)
            is SideControlEdit.GpuLock -> TierTransition.afterGpuLock(side, edit.locked)
            is SideControlEdit.GpuFloor -> TierTransition.afterGpuFloor(side, edit.percent)
            is SideControlEdit.CpuFloor -> TierTransition.afterCpuFloor(side, edit.percent)
            is SideControlEdit.PrimeBoost -> TierTransition.afterPrimeBoost(side, edit.limited)
            is SideControlEdit.Governor -> side
        }
        val writesPowerTargetCaps = when (edit) {
            is SideControlEdit.PowerTarget -> edit.enabled || edit.percent >= 100
            is SideControlEdit.PowerTargetCpuOnly -> nextSide.powerTargetEnabled
            else -> false
        }
        val next = TuningState(if (writesPowerTargetCaps) PowerTier.CUSTOM else current.tier, nextSide)
        val governorLabel = (edit as? SideControlEdit.Governor)?.option?.label

        val owner = ownership()
        val deviceAllowed = when (val now = owner.current()) {
            ClockOwner.Free -> true
            is ClockOwner.BoundGame -> !now.autoTdp
            ClockOwner.GlobalAutoTdp -> false
        }
        if (!deviceAllowed) {
            persist(next, governorLabel)
            return TuningOutcome.Deferred
        }

        val outcome = when (edit) {
            is SideControlEdit.PowerTarget, is SideControlEdit.PowerTargetCpuOnly ->
                if (writesPowerTargetCaps) {
                    applyPowerTargetCaps(policies, nextSide).toOutcome()
                } else {
                    TuningOutcome.Applied()
                }
            is SideControlEdit.GpuLock -> {
                if (gpu == null) return TuningOutcome.Failed(FailureReason.NO_GPU)
                if (edit.locked) {
                    TuningOutcome.Applied(gpuLockLevel = gpuFloor.lockToCurrentCap(gpu.policyPath))
                } else {
                    gpuFloor.setFloorLevel(gpu.policyPath, slowestGpuLevel(gpu))
                    TuningOutcome.Applied()
                }
            }
            is SideControlEdit.GpuFloor -> {
                if (gpu == null) return TuningOutcome.Failed(FailureReason.NO_GPU)
                gpuFloor.setFloorLevel(gpu.policyPath, gpuFloorLevelFor(gpu, edit.percent))
                TuningOutcome.Applied()
            }
            is SideControlEdit.CpuFloor ->
                if (cpuFloor.setFloor(policies, edit.percent)) {
                    TuningOutcome.Applied()
                } else {
                    TuningOutcome.Failed(FailureReason.WRITE_FAILED)
                }
            is SideControlEdit.PrimeBoost -> {
                if (prime == null) return TuningOutcome.Failed(FailureReason.NO_PRIME)
                val target = if (edit.limited) noTurboFreq(prime) else store.lastValues()[prime.id] ?: prime.selectableMaxFreq
                AutoTuneController.applyFreqsToDevice(policies, mapOf(prime.id to target), shell)
                TuningOutcome.Applied()
            }
            is SideControlEdit.Governor -> {
                val chosen = governors.setGovernor(policies, edit.option)
                if (chosen != null) TuningOutcome.Applied(governor = chosen) else TuningOutcome.Failed(FailureReason.WRITE_FAILED)
            }
        }
        val governorFailed = edit is SideControlEdit.Governor && outcome is TuningOutcome.Failed
        persist(next, governorLabel.takeUnless { governorFailed })
        owner.capsWritten()
        outcome
    }

    private suspend fun applyPreset(policies: List<CpuPolicyInfo>, tier: PowerTier): TuningOutcome {
        val result = caps.applyCaps(
            policies = policies,
            values = tierFrequencies(tier, policies),
            isReset = tier == PowerTier.MAX,
            persistAsCustom = false,
        )
        val applied = result.getOrElse { return TuningOutcome.Failed(FailureReason.WRITE_FAILED, it.message) }
        // The cap write only touches scaling_max, so the CPU floor's raised scaling_min must be released here.
        cpuFloor.setFloor(policies, 0)
        return TuningOutcome.Applied(caps = applied, governor = tier.governorLabel?.let { setGovernor(policies, it) })
    }

    private suspend fun restoreCustom(policies: List<CpuPolicyInfo>, snapshot: CustomTuning): TuningOutcome {
        val saved = store.customValues().filterKeys { id -> policies.any { it.id == id } }
        var failure: TuningOutcome.Failed? = null
        var applied: PerformanceRepository.ApplyOutcome? = null
        if (saved.isEmpty()) {
            failure = TuningOutcome.Failed(FailureReason.NO_SAVED_CUSTOM)
        } else {
            caps.applyCaps(policies, saved, isReset = false, persistAsCustom = true)
                .onSuccess { applied = it }
                .onFailure { failure = TuningOutcome.Failed(FailureReason.WRITE_FAILED, it.message) }
        }
        val side = TierTransition.afterCustomRestore(snapshot)
        if (side.powerTargetEnabled) {
            applyPowerTargetCaps(policies, side).onSuccess { applied = it }
        }
        reapplyMinSideControls(policies, side)
        val governor = snapshot.governorLabel?.let { setGovernor(policies, it) }
        return failure ?: TuningOutcome.Applied(caps = applied, governor = governor)
    }

    private suspend fun applyPowerTargetCaps(
        policies: List<CpuPolicyInfo>,
        side: SideControlState,
    ): Result<PerformanceRepository.ApplyOutcome> {
        val result = caps.applyCaps(
            policies = policies,
            values = PowerTargetMath.capsForPercent(policies, side.powerTargetPercent, cpuOnly = side.powerTargetCpuOnly),
            isReset = side.powerTargetPercent >= 100,
            persistAsCustom = true,
        )
        // Any cap apply widens the GPU min_pwrlevel, un-pinning a lock.
        val gpu = policies.firstOrNull { it.isGpu }
        if (side.gpuLocked && gpu != null) gpuFloor.lockToCurrentCap(gpu.policyPath)
        return result
    }

    /** The Custom controls a cap apply releases: GPU lock/floor and CPU floor, plus the prime-boost cap. */
    private fun reapplyMinSideControls(policies: List<CpuPolicyInfo>, side: SideControlState) {
        val gpu = policies.firstOrNull { it.isGpu }
        if (gpu != null) {
            when {
                side.gpuLocked -> gpuFloor.lockToCurrentCap(gpu.policyPath)
                side.gpuFloorPercent > 0 -> gpuFloor.setFloorLevel(gpu.policyPath, gpuFloorLevelFor(gpu, side.gpuFloorPercent))
            }
        }
        if (side.cpuFloorPercent > 0) cpuFloor.setFloor(policies, side.cpuFloorPercent)
        // A Power Target's (lower) prime cap must win over the no-turbo cap.
        val prime = primeOf(policies)
        if (side.primeCoreBoostLimited && !side.powerTargetEnabled && prime != null) {
            AutoTuneController.applyFreqsToDevice(policies, mapOf(prime.id to noTurboFreq(prime)), shell)
        }
    }

    private fun setGovernor(policies: List<CpuPolicyInfo>, label: String): String? =
        GovernorController.OPTIONS.firstOrNull { it.label == label }?.let { governors.setGovernor(policies, it) }

    private suspend fun persist(next: TuningState, governorLabel: String? = null) {
        store.writeState(next)
        if (next.tier != PowerTier.CUSTOM) return
        val side = next.sideControls
        store.writeCustomSnapshot(
            CustomTuning(
                powerTargetEnabled = side.powerTargetEnabled,
                powerTargetPercent = side.powerTargetPercent,
                powerTargetCpuOnly = side.powerTargetCpuOnly,
                gpuLocked = side.gpuLocked,
                gpuFloorPercent = side.gpuFloorPercent,
                cpuFloorPercent = side.cpuFloorPercent,
                primeCoreBoostLimited = side.primeCoreBoostLimited,
                governorLabel = governorLabel ?: store.customSnapshot().governorLabel,
            ),
        )
    }

    private fun Result<PerformanceRepository.ApplyOutcome>.toOutcome(): TuningOutcome = fold(
        onSuccess = { TuningOutcome.Applied(caps = it) },
        onFailure = { TuningOutcome.Failed(FailureReason.WRITE_FAILED, it.message) },
    )

    companion object {
        /** A preset's caps: each policy's max scaled by the Tier's factor, snapped to a supported frequency. */
        fun tierFrequencies(tier: PowerTier, policies: List<CpuPolicyInfo>): Map<Int, Int> {
            val powerSaving = tier == PowerTier.POWER_SAVING
            val primeId = primeOf(policies)?.id
            return policies.associate { policy ->
                val factor = when {
                    policy.isGpu && !powerSaving -> 1.0
                    policy.isGpu -> tier.gpuFactor
                    else -> tier.cpuFactor
                }
                val target = (policy.selectableMaxFreq * factor).toInt()
                var snapped = policy.supportedFrequencies.minByOrNull { abs(it - target) } ?: policy.selectableMaxFreq
                if (powerSaving && policy.id == primeId) snapped = minOf(snapped, noTurboFreq(policy))
                policy.id to snapped
            }
        }

        private fun primeOf(policies: List<CpuPolicyInfo>): CpuPolicyInfo? =
            policies.filterNot { it.isGpu }.maxByOrNull { it.selectableMaxFreq }

        private fun noTurboFreq(policy: CpuPolicyInfo): Int {
            val freqs = policy.supportedFrequencies
            return freqs.getOrNull(freqs.size - 2) ?: freqs.last()
        }

        private fun slowestGpuLevel(gpu: CpuPolicyInfo): Int = (gpu.supportedFrequencies.size - 1).coerceAtLeast(0)

        /** GPU power-level index (0 = fastest) for a floor percentage; the slowest level when cleared. */
        private fun gpuFloorLevelFor(gpu: CpuPolicyInfo, percent: Int): Int {
            val asc = gpu.supportedFrequencies
            if (percent <= 0 || asc.isEmpty()) return slowestGpuLevel(gpu)
            val targetFreq = gpu.selectableMaxFreq * percent / 100
            val pos = asc.indices.minByOrNull { abs(asc[it] - targetFreq) } ?: 0
            return (asc.size - 1 - pos).coerceIn(0, slowestGpuLevel(gpu))
        }
    }
}
