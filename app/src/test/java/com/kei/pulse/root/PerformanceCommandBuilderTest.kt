package com.kei.pulse.root

import com.kei.pulse.model.CpuPolicyInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceCommandBuilderTest {

    private fun cpu(
        id: Int = 0,
        supported: List<Int> = listOf(300_000, 1_500_000, 3_000_000),
        minFreq: Int = 300_000,
    ) = CpuPolicyInfo(
        id = id,
        policyPath = "/p$id",
        scalingMaxPath = "/p$id/scaling_max_freq",
        currentMaxFreq = 3_000_000,
        selectableMaxFreq = 3_000_000,
        observedMaxFreq = 3_000_000,
        minFreq = minFreq,
        supportedFrequencies = supported,
    )

    private val builder = PerformanceCommandBuilder()

    @Test fun `emits the scaling_max write for a valid value`() {
        val script = builder.buildApplyScript(listOf(cpu()), mapOf(0 to 1_500_000), isReset = false)
        assertTrue(script.contains("echo 1500000 > /p0/scaling_max_freq"))
    }

    @Test fun `never echoes a zero frequency`() {
        val script = builder.buildApplyScript(listOf(cpu()), mapOf(0 to 0), isReset = true)
        assertFalse("a 0 freq must never be written", script.contains("echo 0 > /p0/scaling_max_freq"))
    }

    @Test fun `never echoes a negative frequency`() {
        val script = builder.buildApplyScript(listOf(cpu()), mapOf(0 to -1), isReset = false)
        assertFalse(script.contains("echo -1 > /p0/scaling_max_freq"))
    }

    @Test fun `skips a non-positive scaling_min floor`() {
        // floor = supportedFrequencies.min ?: minFreq → 0 here; must not be echoed to scaling_min_freq.
        val policy = cpu(supported = emptyList(), minFreq = 0)
        val script = builder.buildApplyScript(
            listOf(policy), mapOf(0 to 1_500_000), isReset = true, lowerMinPolicyIds = setOf(0),
        )
        assertFalse(script.contains("echo 0 > /p0/scaling_min_freq"))
        // the valid max write still happens
        assertTrue(script.contains("echo 1500000 > /p0/scaling_max_freq"))
    }

    private val odinPolicies = listOf(
        CpuPolicyInfo(
            id = 0,
            policyPath = "/sys/devices/system/cpu/cpufreq/policy0",
            scalingMaxPath = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
            currentMaxFreq = 2_745_600,
            selectableMaxFreq = 3_532_800,
            observedMaxFreq = 3_532_800,
            minFreq = 998_400,
            supportedFrequencies = listOf(998_400, 2_745_600, 3_532_800),
        ),
        CpuPolicyInfo(
            id = 6,
            policyPath = "/sys/devices/system/cpu/cpufreq/policy6",
            scalingMaxPath = "/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq",
            currentMaxFreq = 3_072_000,
            selectableMaxFreq = 4_320_000,
            observedMaxFreq = 4_320_000,
            minFreq = 1_075_200,
            supportedFrequencies = listOf(1_075_200, 3_072_000, 4_320_000),
        ),
    )

    @Test
    fun `builds underclock script without service stop`() {
        val script = builder.buildApplyScript(
            policies = odinPolicies,
            selectedValues = mapOf(0 to 2_745_600, 6 to 3_072_000),
            isReset = false,
        )

        assertTrue(!script.contains("stop "))
        assertTrue(script.contains("echo 2745600 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"))
        assertTrue(script.contains("chmod 444 /sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq"))
    }

    @Test
    fun `lowerMinPolicyIds drops only the named cluster's scaling_min, before the cap, locked read-only`() {
        val script = builder.buildApplyScript(
            policies = odinPolicies,
            selectedValues = mapOf(0 to 2_745_600, 6 to 1_075_200),
            isReset = false,
            lowerMinPolicyIds = setOf(6), // prime only
        )
        // Prime (policy6): min dropped to its floor and locked so a higher cap can't be clamped back.
        assertTrue(script.contains("echo 1075200 > /sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq"))
        assertTrue(script.contains("chmod 444 /sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq"))
        // The min write precedes the max write (kernel enforces max >= min).
        assertTrue(
            script.indexOf("policy6/scaling_min_freq") < script.indexOf("echo 1075200 > /sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq"),
        )
        // The perf cluster (policy0) is NOT touched — that's what keeps its cap biting.
        assertTrue(!script.contains("policy0/scaling_min_freq"))
    }

    @Test
    fun `default leaves scaling_min_freq untouched`() {
        val script = builder.buildApplyScript(
            policies = odinPolicies,
            selectedValues = mapOf(0 to 2_745_600, 6 to 3_072_000),
            isReset = false,
        )
        assertTrue(!script.contains("scaling_min_freq"))
    }

    @Test
    fun `builds reset script without service restart`() {
        val script = builder.buildApplyScript(
            policies = odinPolicies,
            selectedValues = mapOf(0 to 3_532_800, 6 to 4_320_000),
            isReset = true,
        )

        assertTrue(script.contains("chmod 644 /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"))
        assertTrue(!script.contains("start "))
        assertTrue(!script.contains("stop perfd"))
    }

    @Test
    fun `prime boost-limit routing lowers and locks the prime min before its no-turbo max, perf untouched`() {
        val script = builder.buildApplyScript(
            policies = odinPolicies,
            selectedValues = mapOf(6 to 3_072_000), // only the prime is written (the boost limit)
            isReset = false,
            lowerMinPolicyIds = setOf(6), // prime id, derived from the full set by highest selectableMaxFreq
        )
        // The prime min is dropped to its floor and 444-locked so a sub-min target could never be rejected.
        assertTrue(script.contains("echo 1075200 > /sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq"))
        assertTrue(script.contains("chmod 444 /sys/devices/system/cpu/cpufreq/policy6/scaling_min_freq"))
        // …before the no-turbo max, which is also locked read-only.
        assertTrue(
            script.indexOf("policy6/scaling_min_freq") <
                script.indexOf("echo 3072000 > /sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq"),
        )
        assertTrue(script.contains("chmod 444 /sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq"))
        // The perf cluster is never touched by a prime boost limit (no min churn, no max stomp).
        assertTrue(!script.contains("policy0/scaling_min_freq"))
        assertTrue(!script.contains("policy0/scaling_max_freq"))
    }

    @Test
    fun `holding a partial cap set leaves an uncapped prime fully untouched even when it is the lowerMin target`() {
        // Bug 9 re-assert: the FULL policy set is passed (so the prime is identified correctly), but only the
        // clusters the binding actually capped are in the freq map. Here the perf cluster (policy0) is held and
        // the prime (policy6) is absent (uncapped).
        val script = builder.buildApplyScript(
            policies = odinPolicies,
            selectedValues = mapOf(0 to 2_745_600), // only the perf cluster is held
            isReset = false,
            lowerMinPolicyIds = setOf(6), // prime id derived from the full set
        )
        // The perf cap is re-asserted and locked read-only.
        assertTrue(script.contains("echo 2745600 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"))
        assertTrue(script.contains("chmod 444 /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"))
        // The uncapped prime is NOT written at all — neither its min (despite being the lowerMin target) nor its
        // max — because it has no entry in the freq map. This is what makes re-asserting a partial cap set safe.
        assertTrue(!script.contains("policy6/scaling_min_freq"))
        assertTrue(!script.contains("policy6/scaling_max_freq"))
    }
}
