package com.kei.pulse.data

import com.kei.pulse.model.CpuPolicyInfo
import com.kei.pulse.root.PrivilegedShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThorPolicyWritesTest {
    private val cpu = listOf(0, 3, 4, 7).map { id ->
        val path = "/sys/devices/system/cpu/cpufreq/policy$id"
        CpuPolicyInfo(
            id = id,
            policyPath = path,
            scalingMaxPath = "$path/scaling_max_freq",
            currentMaxFreq = 2_000_000,
            selectableMaxFreq = if (id == 7) 3_187_200 else 2_000_000,
            observedMaxFreq = 3_187_200,
            minFreq = 300_000,
            supportedFrequencies = listOf(300_000, 1_000_000, 2_000_000),
        )
    }
    private val gpu = cpu.first().copy(
        id = CpuPolicyInfo.GPU_POLICY_ID,
        policyPath = "/sys/class/kgsl/kgsl-3d0",
        scalingMaxPath = "/sys/class/kgsl/kgsl-3d0/max_pwrlevel",
    )
    private val commands = mutableListOf<String>()
    private val shell = PrivilegedShell { command ->
        assertTrue("PServer truncates ${command.length} characters: $command", command.length <= 255)
        commands += command
        when {
            "scaling_available_governors" in command -> "walt performance powersave"
            "scaling_max_freq" in command -> "2000000"
            else -> ""
        }
    }

    @Test
    fun `governor selection writes every CPU policy within the inline limit`() {
        assertEquals("performance", GovernorController(shell).setGovernor(cpu + gpu, GovernorController.OPTIONS.first()))
        assertWrites(cpu, "scaling_governor", "performance")
    }

    @Test
    fun `raw governor restore writes every CPU policy within the inline limit`() {
        assertTrue(GovernorController(shell).setGovernorRaw(cpu + gpu, "walt"))
        assertWrites(cpu, "scaling_governor", "walt")
    }

    @Test
    fun `floor raise writes non-prime policies within the inline limit`() {
        assertTrue(CpuFloorController(shell).setFloor(cpu + gpu, 50))
        assertWrites(cpu.dropLast(1), "scaling_min_freq", "1000000")
    }

    @Test
    fun `floor reset writes every CPU policy within the inline limit`() {
        assertTrue(CpuFloorController(shell).setFloor(cpu + gpu, 0))
        assertWrites(cpu, "scaling_min_freq", "300000")
    }

    private fun assertWrites(policies: List<CpuPolicyInfo>, node: String, value: String) {
        val writes = commands.filter { it.startsWith("chmod ") }
        assertEquals(policies.map { p ->
            val path = "${p.policyPath}/$node"
            "chmod 666 $path; echo $value > $path; chmod 644 $path"
        }, writes)
    }
}
