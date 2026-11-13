package com.kei.pulse.data

import com.kei.pulse.model.CpuPolicyInfo
import com.kei.pulse.root.PrivilegedShell
import com.kei.pulse.root.RootSupport
import com.kei.pulse.root.CpuClockProtection
import java.io.File
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
    private val scripts = mutableListOf<String>()
    private val transport = PrivilegedShell { command ->
        assertTrue("PServer truncates ${command.length} characters: $command", command.length <= RootSupport.PSERVER_COMMAND_LIMIT)
        commands += command
        when {
            command.startsWith("sh '") -> {
                val file = File(command.removePrefix("sh '").removeSuffix("'"))
                scripts += file.readText()
                CpuClockProtection.SUCCESS
            }
            "scaling_available_governors" in command -> "walt performance powersave"
            "scaling_max_freq" in command -> "2000000"
            else -> ""
        }
    }
    private val shell = PrivilegedShell { RootSupport.runRootCommand(it, transport) }

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
        assertFloorWrites("1000000", protect = 1)
    }

    @Test
    fun `floor reset leaves the prime cap minimum untouched within the inline limit`() {
        assertTrue(CpuFloorController(shell).setFloor(cpu.map { it.copy(minFreq = 1_000_000) } + gpu, 0))
        assertFloorWrites("300000", protect = 0)
    }

    private fun assertFloorWrites(value: String, protect: Int) {
        assertEquals(cpu.dropLast(1).map { "cpu_write '${it.policyPath}/scaling_min_freq' $value $protect" },
            scripts.single().lineSequence().filter { it.startsWith("cpu_write ") }.toList())
    }

    private fun assertWrites(policies: List<CpuPolicyInfo>, node: String, value: String) {
        val writes = commands.filter { it.startsWith("chmod ") }
        assertEquals(policies.map { p ->
            val path = "${p.policyPath}/$node"
            "chmod 666 $path; echo $value > $path; chmod 644 $path"
        }, writes)
    }
}
