package com.kei.pulse.root

import com.kei.pulse.model.CpuPolicyInfo

class PerformanceCommandBuilder {

    /** [lowerMinPolicyIds] selects CPU minimums to lower before capping, or release on reset. */
    fun buildApplyScript(
        policies: List<CpuPolicyInfo>,
        selectedValues: Map<Int, Int>,
        isReset: Boolean,
        lowerMinPolicyIds: Set<Int> = emptySet(),
    ): String {
        val lines = mutableListOf<String>()

        policies.forEach { policy ->
            val value = selectedValues[policy.id] ?: return@forEach
            if (policy.isGpu) {
                appendGpuLevel(lines, policy, value)
            } else {
                if (value <= 0) return@forEach
                val protect = !isReset && value < policy.selectableMaxFreq
                if (policy.id in lowerMinPolicyIds) {
                    val floor = policy.supportedFrequencies.minOrNull() ?: policy.minFreq
                    val path = "${policy.policyPath}/scaling_min_freq"
                    if (floor > 0 && (protect || isReset)) {
                        lines += CpuClockProtection.write(path, floor, protect)
                    } else {
                        lines += CpuClockProtection.release(path)
                    }
                }
                lines += CpuClockProtection.write(policy.scalingMaxPath, value, protect)
            }
        }

        return CpuClockProtection.script(lines.joinToString("\n"))
    }

    /**
     * Adreno is capped by power-level INDEX (fastest = 0). The kernel clamps `max_pwrlevel`
     * so it can never be a higher index (slower level) than `min_pwrlevel`. If the device's
     * default `min_pwrlevel` sits above our target the cap silently snaps back — which is
     * why only level 0 (uncapped) "stuck" before. So we widen `min_pwrlevel` to the slowest
     * level first (full downscale headroom), then set the ceiling.
     */
    private fun appendGpuLevel(
        lines: MutableList<String>,
        policy: CpuPolicyInfo,
        valueKHz: Int,
    ) {
        val maxLevel = gpuPowerLevelFor(policy, valueKHz)
        val slowestLevel = (policy.supportedFrequencies.size - 1).coerceAtLeast(maxLevel)
        val minPath = "${policy.policyPath}/min_pwrlevel"
        // Always lock the GPU bounds read-only (444). On the Max tier (isReset) the CPU nodes
        // are intentionally left writable for stock behaviour, but doing the same for the GPU
        // lets the vendor performance daemon stomp min_pwrlevel back up and floor the GPU
        // mid-range (~900MHz). Locking min=slowest / max=ceiling keeps it free to idle down
        // and scale up to its cap.
        write(lines, minPath, slowestLevel.toLong(), "444")
        write(lines, policy.scalingMaxPath, maxLevel.toLong(), "444")
    }

    private fun write(lines: MutableList<String>, path: String, value: Long, mode: String) {
        lines += "if [ \"\$(cat $path)\" != $value ] || [ \"\$(stat -c %a $path)\" != $mode ]; then"
        lines += "chmod 666 $path"
        lines += "echo $value > $path"
        lines += "chmod $mode $path"
        lines += "fi"
    }

    /** ascending kHz table -> power level (fastest = 0). */
    private fun gpuPowerLevelFor(policy: CpuPolicyInfo, valueKHz: Int): Int {
        val asc = policy.supportedFrequencies
        if (asc.isEmpty()) return 0
        val pos = asc.indexOf(valueKHz).let { exact ->
            if (exact >= 0) exact else asc.indices.minByOrNull { kotlin.math.abs(asc[it] - valueKHz) } ?: asc.lastIndex
        }
        return (asc.size - 1 - pos).coerceIn(0, asc.lastIndex)
    }
}
