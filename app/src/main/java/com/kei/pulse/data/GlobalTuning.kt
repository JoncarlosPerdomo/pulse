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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    suspend fun applyDisplayProfileById(profileId: String): Result<PerformanceRepository.ApplyOutcome>
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

    /** A side-control edit saved, but not written: AutoTDP owns the clocks. */
    data object Deferred : TuningOutcome

    /**
     * A Tier switch neither saved nor written: a bound game owns the clocks, and its exit restore would put back
     * the pre-game Tier and caps anyway, leaving any saved side controls out of step with them.
     */
    data object Blocked : TuningOutcome

    data class Failed(val reason: FailureReason, val message: String? = null) : TuningOutcome
}

enum class FailureReason { PSERVER_UNAVAILABLE, NO_POLICIES, NO_SAVED_CUSTOM, NO_GPU, NO_PRIME, WRITE_FAILED }

/**
 * The device-wide tuning mode: switching Tier and editing the side controls. Every entry point (app, tile,
 * Quick Access) goes through here, so a Tier switch means the same thing everywhere: caps, side controls,
 * floors, governor, the persisted state and the Custom snapshot, deferring to whoever owns the clocks.
 *
 * Lock order: this module's mutex is taken before [ClockOwnership.withOwnership]'s lock; never call in while
 * holding that one.
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

    internal suspend fun expectedCaps(policies: List<CpuPolicyInfo>): Map<Int, Int> =
        expectedCapsFor(store.state(), policies, store.customValues())

    internal suspend fun clockOwner(): ClockOwner = ownership().withOwnership { owner }

    suspend fun switchTo(tier: PowerTier): TuningOutcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!caps.isAvailable) return@withLock TuningOutcome.Failed(FailureReason.PSERVER_UNAVAILABLE)
            val policies = caps.policies()
            if (policies.isEmpty()) return@withLock TuningOutcome.Failed(FailureReason.NO_POLICIES)
            val snapshot = store.customSnapshot()
            val next = TuningState(
                tier = tier,
                sideControls = if (tier == PowerTier.CUSTOM) {
                    TierTransition.afterCustomRestore(snapshot)
                } else {
                    TierTransition.afterPreset(store.state().sideControls)
                },
            )
            ownership().withOwnership {
                when (owner) {
                    is ClockOwner.BoundGame -> return@withOwnership TuningOutcome.Blocked
                    ClockOwner.GlobalAutoTdp -> releaseGlobalAutoTdp()
                    ClockOwner.Free -> Unit
                }
                val outcome = if (tier == PowerTier.CUSTOM) restoreCustom(policies, snapshot) else applyPreset(policies, tier)
                val presetFailed = outcome is TuningOutcome.Failed && tier != PowerTier.CUSTOM
                if (!presetFailed) persist(next)
                capsWritten()
                outcome
            }
        }
    }

    /** Re-apply the active Tier (e.g. after AutoTDP hands the clocks back). */
    suspend fun reapply(): TuningOutcome = switchTo(store.state().tier)

    suspend fun edit(edit: SideControlEdit): TuningOutcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            val policies = caps.policies()
            if (policies.isEmpty()) return@withLock TuningOutcome.Failed(FailureReason.NO_POLICIES)
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

            ownership().withOwnership {
                val deviceAllowed = when (val now = owner) {
                    ClockOwner.Free -> true
                    is ClockOwner.BoundGame -> !now.autoTdp
                    ClockOwner.GlobalAutoTdp -> false
                }
                if (!deviceAllowed) {
                    persist(next, governorLabel)
                    return@withOwnership TuningOutcome.Deferred
                }

                val outcome = when (edit) {
                    is SideControlEdit.PowerTarget, is SideControlEdit.PowerTargetCpuOnly ->
                        if (writesPowerTargetCaps) {
                            applyPowerTargetCaps(policies, nextSide).toOutcome()
                        } else {
                            TuningOutcome.Applied()
                        }
                    is SideControlEdit.GpuLock -> {
                        if (gpu == null) return@withOwnership TuningOutcome.Failed(FailureReason.NO_GPU)
                        if (edit.locked) {
                            TuningOutcome.Applied(gpuLockLevel = gpuFloor.lockToCurrentCap(gpu.policyPath))
                        } else {
                            gpuFloor.setFloorLevel(gpu.policyPath, slowestGpuLevel(gpu))
                            TuningOutcome.Applied()
                        }
                    }
                    is SideControlEdit.GpuFloor -> {
                        if (gpu == null) return@withOwnership TuningOutcome.Failed(FailureReason.NO_GPU)
                        val level = gpuFloorLevel(gpu, edit.percent)
                            ?: return@withOwnership TuningOutcome.Failed(FailureReason.WRITE_FAILED)
                        gpuFloor.setFloorLevel(gpu.policyPath, level)
                        TuningOutcome.Applied()
                    }
                    is SideControlEdit.CpuFloor ->
                        if (cpuFloor.setFloor(policies, edit.percent)) {
                            TuningOutcome.Applied()
                        } else {
                            TuningOutcome.Failed(FailureReason.WRITE_FAILED)
                        }
                    is SideControlEdit.PrimeBoost -> {
                        if (prime == null) return@withOwnership TuningOutcome.Failed(FailureReason.NO_PRIME)
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
                capsWritten()
                outcome
            }
        }
    }

    suspend fun applyProfile(profileId: String): TuningOutcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            val policies = caps.policies()
            ownership().withOwnership {
                when (owner) {
                    is ClockOwner.BoundGame -> return@withOwnership TuningOutcome.Blocked
                    ClockOwner.GlobalAutoTdp -> releaseGlobalAutoTdp()
                    ClockOwner.Free -> Unit
                }
                val outcome = writeCapsReassertingGpuUnderOwnership(policies) { caps.applyDisplayProfileById(profileId) }.toOutcome()
                capsWritten()
                outcome
            }
        }
    }

    /**
     * Runs [writeCaps], then re-pins the GPU lock or floor that the cap write released. A non-null [gpuFloorPercent]
     * is a per-app binding's floor and replaces the global lock and floor for this write. For callers that hold
     * neither this module's lock nor clock ownership; the global tuning it reads can't change mid-write.
     */
    suspend fun <T> writeCapsReassertingGpu(
        policies: List<CpuPolicyInfo>,
        gpuFloorPercent: Int? = null,
        writeCaps: suspend () -> T,
    ): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            ownership().withOwnership { writeCapsReassertingGpuUnderOwnership(policies, gpuFloorPercent, writeCaps) }
        }
    }

    /** [writeCapsReassertingGpu] for a caller already inside [ClockOwnership.withOwnership], which must not re-enter it. */
    suspend fun <T> writeCapsReassertingGpuUnderOwnership(
        policies: List<CpuPolicyInfo>,
        gpuFloorPercent: Int? = null,
        writeCaps: suspend () -> T,
    ): T = withContext(Dispatchers.IO) {
        val global = store.state().sideControls
        val side = if (gpuFloorPercent == null) global else global.copy(gpuLocked = false, gpuFloorPercent = gpuFloorPercent)
        val result = writeCaps()
        reapplyGpuControl(policies, side)
        result
    }

    private suspend fun applyCaps(
        policies: List<CpuPolicyInfo>,
        values: Map<Int, Int>,
        isReset: Boolean,
        persistAsCustom: Boolean,
        side: SideControlState,
    ): Result<PerformanceRepository.ApplyOutcome> {
        val result = caps.applyCaps(policies, values, isReset, persistAsCustom)
        reapplyControlsReleasedByCaps(policies, side)
        return result
    }

    private suspend fun applyPreset(policies: List<CpuPolicyInfo>, tier: PowerTier): TuningOutcome {
        val result = applyCaps(
            policies = policies,
            values = tierFrequencies(tier, policies),
            isReset = tier == PowerTier.MAX,
            persistAsCustom = false,
            side = SideControlState.CLEARED,
        )
        val applied = result.getOrElse { return TuningOutcome.Failed(FailureReason.WRITE_FAILED, it.message) }
        cpuFloor.setFloor(policies, 0)
        return TuningOutcome.Applied(caps = applied, governor = tier.governorLabel?.let { setGovernor(policies, it) })
    }

    private suspend fun restoreCustom(policies: List<CpuPolicyInfo>, snapshot: CustomTuning): TuningOutcome {
        val side = TierTransition.afterCustomRestore(snapshot)
        val saved = store.customValues().filterKeys { id -> policies.any { it.id == id } }
        var failure: TuningOutcome.Failed? = null
        var applied: PerformanceRepository.ApplyOutcome? = null
        if (saved.isEmpty()) {
            failure = TuningOutcome.Failed(FailureReason.NO_SAVED_CUSTOM)
        } else {
            applyCaps(policies, saved, isReset = false, persistAsCustom = true, side = side)
                .onSuccess { applied = it }
                .onFailure { failure = TuningOutcome.Failed(FailureReason.WRITE_FAILED, it.message) }
        }
        if (side.powerTargetEnabled) {
            applyPowerTargetCaps(policies, side)
                .onSuccess { applied = it }
                .onFailure { failure = TuningOutcome.Failed(FailureReason.WRITE_FAILED, it.message) }
        }
        val governor = snapshot.governorLabel?.let { setGovernor(policies, it) }
        return failure ?: TuningOutcome.Applied(caps = applied, governor = governor)
    }

    private suspend fun applyPowerTargetCaps(
        policies: List<CpuPolicyInfo>,
        side: SideControlState,
    ): Result<PerformanceRepository.ApplyOutcome> {
        val result = applyCaps(
            policies = policies,
            values = PowerTargetMath.capsForPercent(policies, side.powerTargetPercent, cpuOnly = side.powerTargetCpuOnly),
            isReset = side.powerTargetPercent >= 100,
            persistAsCustom = true,
            side = side,
        )
        return result
    }

    private fun reapplyGpuControl(policies: List<CpuPolicyInfo>, side: SideControlState) {
        val gpu = policies.firstOrNull { it.isGpu } ?: return
        if (side.gpuLocked) {
            gpuFloor.lockToCurrentCap(gpu.policyPath)
        } else {
            gpuFloorLevel(gpu, side.gpuFloorPercent)?.let { gpuFloor.setFloorLevel(gpu.policyPath, it) }
        }
    }

    private fun gpuFloorLevel(gpu: CpuPolicyInfo, percent: Int): Int? =
        gpuFloor.readCeilingLevel(gpu.policyPath)?.let { gpuFloorLevelFor(gpu, percent, it) }

    private fun reapplyControlsReleasedByCaps(policies: List<CpuPolicyInfo>, side: SideControlState) {
        reapplyGpuControl(policies, side)
        if (side.cpuFloorPercent > 0) cpuFloor.setFloor(policies, side.cpuFloorPercent)
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

        internal fun expectedCapsFor(
            state: TuningState,
            policies: List<CpuPolicyInfo>,
            customValues: Map<Int, Int>,
        ): Map<Int, Int> {
            val side = state.sideControls
            val caps = when {
                state.tier != PowerTier.CUSTOM -> tierFrequencies(state.tier, policies)
                side.powerTargetEnabled -> PowerTargetMath.capsForPercent(policies, side.powerTargetPercent, side.powerTargetCpuOnly)
                else -> customValues.filterKeys { id -> policies.any { it.id == id } }
            }
            val prime = primeOf(policies)
            return if (side.primeCoreBoostLimited && !side.powerTargetEnabled && prime != null) {
                caps + (prime.id to noTurboFreq(prime))
            } else caps
        }

        internal fun primeOf(policies: List<CpuPolicyInfo>): CpuPolicyInfo? =
            policies.filterNot { it.isGpu }.maxByOrNull { it.selectableMaxFreq }

        internal fun noTurboFreq(policy: CpuPolicyInfo): Int {
            val freqs = policy.supportedFrequencies
            return freqs.getOrNull(freqs.size - 2) ?: freqs.last()
        }

        private fun slowestGpuLevel(gpu: CpuPolicyInfo): Int = (gpu.supportedFrequencies.size - 1).coerceAtLeast(0)

        internal fun gpuFloorMhz(gpu: CpuPolicyInfo?, level: Int?): Int? {
            val frequencies = gpu?.supportedFrequencies ?: return null
            if (level == null || level !in frequencies.indices) return null
            return frequencies[frequencies.lastIndex - level] / 1000
        }

        internal fun gpuFloorLevelFor(gpu: CpuPolicyInfo, percent: Int, ceiling: Int = 0): Int =
            requestedGpuFloorLevel(gpu, percent).coerceAtLeast(ceiling)

        private fun requestedGpuFloorLevel(gpu: CpuPolicyInfo, percent: Int): Int {
            val asc = gpu.supportedFrequencies
            if (percent <= 0 || asc.isEmpty()) return slowestGpuLevel(gpu)
            val targetFreq = gpu.selectableMaxFreq * percent / 100
            val pos = asc.indices.minByOrNull { abs(asc[it] - targetFreq) } ?: 0
            return (asc.size - 1 - pos).coerceIn(0, slowestGpuLevel(gpu))
        }
    }
}
