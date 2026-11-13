package com.kei.pulse.root

import com.kei.pulse.model.CpuPolicyInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceCommandBuilderTest {
    private val builder = PerformanceCommandBuilder()
    private fun cpu(id: Int = 0, supported: List<Int> = listOf(300_000, 1_500_000, 3_000_000), min: Int = 300_000) = CpuPolicyInfo(
        id = id,
        policyPath = "/p$id",
        scalingMaxPath = "/p$id/scaling_max_freq",
        currentMaxFreq = 3_000_000,
        selectableMaxFreq = 3_000_000,
        observedMaxFreq = 3_000_000,
        minFreq = min,
        supportedFrequencies = supported,
    )

    @Test fun `caps below the selectable maximum acquire protection`() {
        val script = builder.buildApplyScript(listOf(cpu()), mapOf(0 to 1_500_000), false)
        assertTrue(script.contains("cpu_write '/p0/scaling_max_freq' 1500000 1"))
    }

    @Test fun `non-positive caps never reach the CPU writer`() {
        for (value in listOf(0, -1)) {
            val script = builder.buildApplyScript(listOf(cpu()), mapOf(0 to value), true)
            assertFalse(script.contains("'/p0/scaling_max_freq'"))
        }
    }

    @Test fun `non-positive minimum is not written`() {
        val script = builder.buildApplyScript(listOf(cpu(supported = emptyList(), min = 0)), mapOf(0 to 1_500_000), true, setOf(0))
        assertFalse(script.contains("cpu_write '/p0/scaling_min_freq'"))
        assertTrue(script.contains("cpu_write '/p0/scaling_max_freq' 1500000 0"))
    }

    @Test fun `only selected minimum policies are lowered before the cap`() {
        val script = builder.buildApplyScript(listOf(cpu(0), cpu(6)), mapOf(0 to 1_500_000, 6 to 1_500_000), false, setOf(6))
        assertTrue(script.contains("cpu_write '/p6/scaling_min_freq' 300000 1"))
        assertTrue(script.indexOf("cpu_write '/p6/scaling_min_freq'") < script.indexOf("cpu_write '/p6/scaling_max_freq'"))
        assertFalse(script.contains("'/p0/scaling_min_freq'"))
    }

    @Test fun `top selectable cap releases protection and leaves boost available`() {
        val script = builder.buildApplyScript(listOf(cpu()), mapOf(0 to 3_000_000), false, setOf(0))
        assertTrue(script.contains("cpu_release '/p0/scaling_min_freq'"))
        assertTrue(script.contains("cpu_write '/p0/scaling_max_freq' 3000000 0"))
    }

    @Test fun `reset releases all selected CPU minimums and caps`() {
        val script = builder.buildApplyScript(listOf(cpu(0), cpu(6)), mapOf(0 to 3_000_000, 6 to 3_000_000), true, setOf(0, 6))
        for (id in listOf(0, 6)) {
            assertTrue(script.contains("cpu_write '/p$id/scaling_min_freq' 300000 0"))
            assertTrue(script.contains("cpu_write '/p$id/scaling_max_freq' 3000000 0"))
        }
    }

    @Test fun `partial cap map leaves an absent prime untouched`() {
        val script = builder.buildApplyScript(listOf(cpu(0), cpu(6)), mapOf(0 to 1_500_000), false, setOf(6))
        assertTrue(script.contains("cpu_write '/p0/scaling_max_freq' 1500000 1"))
        assertFalse(script.contains("'/p6/scaling_min_freq'"))
        assertFalse(script.contains("'/p6/scaling_max_freq'"))
    }
}
