package com.kei.pulse.ui

import com.kei.pulse.data.GlobalTuning.Companion.gpuFloorLevelFor
import com.kei.pulse.data.GlobalTuning.Companion.gpuFloorMhz
import com.kei.pulse.model.ClockOwner
import com.kei.pulse.model.CpuPolicyInfo
import kotlin.math.abs

internal enum class TuningInForce(val label: String) {
    Applied("Applied"),
    NotApplied("Not applied"),
    Unverified("Unverified"),
}

internal fun globalTuningStatus(status: TuningInForce, owner: ClockOwner): TuningInForce? =
    status.takeIf { owner == ClockOwner.Free }

internal fun capsInForce(
    policies: List<CpuPolicyInfo>,
    expected: Map<Int, Int>,
    actual: Map<Int, Int>,
): TuningInForce {
    if (policies.isEmpty() || expected.isEmpty()) return TuningInForce.Unverified
    var missing = false
    for (policy in policies) {
        val target = expected[policy.id]
        val live = actual[policy.id]
        if (target == null || live == null) {
            missing = true
            continue
        }
        val nearestDistance = policy.supportedFrequencies.minOfOrNull { abs(it.toLong() - target) }
        val uncapped = target >= (policy.supportedFrequencies.maxOrNull() ?: Int.MAX_VALUE)
        val matches = (uncapped && live >= target) || live == target ||
            (live in policy.supportedFrequencies && abs(live.toLong() - target) == nearestDistance)
        if (!matches) return TuningInForce.NotApplied
    }
    return if (missing) TuningInForce.Unverified else TuningInForce.Applied
}

internal fun gpuFloorInForce(
    gpu: CpuPolicyInfo?,
    percent: Int,
    actualLevel: Int?,
    ceiling: Int? = 0,
): TuningInForce {
    if (gpu == null || ceiling == null || gpuFloorMhz(gpu, actualLevel) == null) return TuningInForce.Unverified
    return if (gpuFloorLevelFor(gpu, percent, ceiling) == actualLevel) TuningInForce.Applied else TuningInForce.NotApplied
}
