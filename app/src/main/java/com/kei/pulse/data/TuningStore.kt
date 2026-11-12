package com.kei.pulse.data

import com.kei.pulse.model.CustomTuning
import com.kei.pulse.model.PowerTier
import com.kei.pulse.model.SideControlState
import kotlinx.coroutines.flow.first

data class TuningState(val tier: PowerTier, val sideControls: SideControlState)

/** The persisted half of global tuning: the active Tier, the side controls, and the Custom snapshot. */
interface TuningStore {
    suspend fun state(): TuningState
    suspend fun writeState(state: TuningState)
    suspend fun customSnapshot(): CustomTuning
    suspend fun writeCustomSnapshot(snapshot: CustomTuning)

    /** The Custom values map (policy id → kHz) saved by caps applied with `persistAsCustom`. */
    suspend fun customValues(): Map<Int, Int>

    /** The most recently applied caps (policy id → kHz). */
    suspend fun lastValues(): Map<Int, Int>
}

class DataStoreTuningStore(
    private val settings: SettingsStorage,
    private val profiles: ProfileStorage,
) : TuningStore {
    override suspend fun state(): TuningState {
        val s = settings.settings.first()
        return TuningState(
            tier = PowerTier.entries.firstOrNull { it.label == s.activeTierLabel } ?: PowerTier.CUSTOM,
            sideControls = SideControlState(
                powerTargetEnabled = s.powerTargetEnabled,
                powerTargetPercent = s.powerTargetPercent,
                powerTargetCpuOnly = s.powerTargetCpuOnly,
                gpuLocked = s.gpuLocked,
                gpuFloorPercent = s.gpuFloorPercent,
                cpuFloorPercent = s.cpuFloorPercent,
                primeCoreBoostLimited = s.primeCoreBoostLimited,
            ),
        )
    }

    override suspend fun writeState(state: TuningState) {
        val side = state.sideControls
        settings.persistTuningState(
            powerTargetEnabled = side.powerTargetEnabled,
            powerTargetPercent = side.powerTargetPercent,
            powerTargetCpuOnly = side.powerTargetCpuOnly,
            gpuLocked = side.gpuLocked,
            gpuFloorPercent = side.gpuFloorPercent,
            cpuFloorPercent = side.cpuFloorPercent,
            activeTierLabel = state.tier.label,
            primeCoreBoostLimited = side.primeCoreBoostLimited,
        )
    }

    override suspend fun customSnapshot(): CustomTuning = settings.customTuning.first()

    override suspend fun writeCustomSnapshot(snapshot: CustomTuning) = settings.persistCustomTuning(snapshot)

    override suspend fun customValues(): Map<Int, Int> = profiles.customValues.first()

    override suspend fun lastValues(): Map<Int, Int> = profiles.lastValues.first()
}
