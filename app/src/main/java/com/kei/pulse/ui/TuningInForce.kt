package com.kei.pulse.ui

import com.kei.pulse.data.GlobalTuning.Companion.gpuFloorLevelFor
import com.kei.pulse.model.CpuPolicyInfo
import kotlin.math.abs

internal sealed interface TuningInForce {
    data object Applied : TuningInForce
    data object NotApplied : TuningInForce
    data object Unknown : TuningInForce
}

internal fun capsInForce(
    policies: List<CpuPolicyInfo>,
    expected: Map<Int, Int>,
    actual: Map<Int, Int>,
): TuningInForce {
    if (policies.isEmpty() || expected.isEmpty()) return TuningInForce.Unknown
    var missing = false
    for (policy in policies) {
        val target = expected[policy.id]
        val live = actual[policy.id]
        if (target == null || live == null) {
            missing = true
            continue
        }
        val nearestDistance = policy.supportedFrequencies.minOfOrNull { abs(it.toLong() - target) }
        val matches = live == target || (live in policy.supportedFrequencies &&
            abs(live.toLong() - target) == nearestDistance)
        if (!matches) return TuningInForce.NotApplied
    }
    return if (missing) TuningInForce.Unknown else TuningInForce.Applied
}

internal fun gpuFloorMhz(gpu: CpuPolicyInfo?, level: Int?): Int? {
    val frequencies = gpu?.supportedFrequencies ?: return null
    if (level == null || level !in frequencies.indices) return null
    return frequencies[frequencies.lastIndex - level] / 1000
}

internal fun gpuFloorInForce(gpu: CpuPolicyInfo?, percent: Int, actualLevel: Int?): TuningInForce {
    if (gpu == null || gpu.supportedFrequencies.isEmpty()) return TuningInForce.Unknown
    val expectedLevel = gpuFloorLevelFor(gpu, percent)
    if (gpuFloorMhz(gpu, actualLevel) == null) return TuningInForce.Unknown
    return if (expectedLevel == actualLevel) TuningInForce.Applied else TuningInForce.NotApplied
}

internal fun TuningInForce.statusLabel(): String = when (this) {
    TuningInForce.Applied -> "Applied"
    TuningInForce.NotApplied -> "Not applied"
    TuningInForce.Unknown -> "Unverified"
}
