package com.kei.pulse.data

import com.kei.pulse.model.ClockOwner
import com.kei.pulse.model.ClockOwnership
import com.kei.pulse.model.ClockSession
import com.kei.pulse.model.CpuPolicyInfo
import com.kei.pulse.model.CustomTuning
import com.kei.pulse.model.PowerTargetMath
import com.kei.pulse.model.PowerTier
import com.kei.pulse.model.SideControlState
import com.kei.pulse.root.PrivilegedShell
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalTuningTest {

    private val little = policy(0, listOf(300_000, 1_000_000, 1_500_000, 2_000_000))
    private val prime = policy(7, listOf(300_000, 2_000_000, 3_000_000, 4_200_000))
    private val gpu = policy(CpuPolicyInfo.GPU_POLICY_ID, listOf(200_000, 500_000, 800_000), path = "/sys/class/kgsl/kgsl-3d0")
    private val policies = listOf(little, prime, gpu)

    private val events = mutableListOf<String>()
    private val shell = FakeShell(events)
    private val store = InMemoryTuningStore()
    private val caps = FakeCaps(store, policies, events)
    private val ownership = FakeOwnership(events)
    private val tuning = GlobalTuning(store, caps, shell, { ownership })

    private val customSide = SideControlState.CLEARED.copy(
        powerTargetEnabled = true,
        powerTargetPercent = 69,
        cpuFloorPercent = 60,
    )

    private fun givenCustomSetup() {
        store.state = TuningState(PowerTier.CUSTOM, customSide)
        store.snapshot = CustomTuning(
            powerTargetEnabled = true,
            powerTargetPercent = 69,
            cpuFloorPercent = 60,
            governorLabel = "Balanced",
        )
        store.custom = mapOf(little.id to 1_500_000, prime.id to 3_000_000)
    }

    @Test
    fun `Custom to Max to Custom releases then restores the side controls`() = runTest {
        givenCustomSetup()

        tuning.switchTo(PowerTier.MAX)

        val maxApply = caps.calls.single()
        assertTrue(maxApply.isReset)
        assertFalse(maxApply.persistAsCustom)
        assertTrue("CPU floor released", shell.wrote("policy0/scaling_min_freq", "echo 300000"))
        assertTrue("Max governor set", shell.wrote("scaling_governor", "echo performance"))
        assertEquals(PowerTier.MAX, store.state.tier)
        assertEquals(SideControlState.CLEARED.copy(powerTargetPercent = 69), store.state.sideControls)
        assertEquals("Custom snapshot untouched by a preset", 69, store.snapshot.powerTargetPercent)

        caps.calls.clear()
        shell.commands.clear()
        val savedCustom = store.custom
        val outcome = tuning.switchTo(PowerTier.CUSTOM)

        assertTrue(outcome is TuningOutcome.Applied)
        assertEquals(
            listOf(savedCustom, PowerTargetMath.capsForPercent(policies, 69, cpuOnly = false)),
            caps.calls.map { it.values },
        )
        assertTrue("CPU floor raised again", shell.wrote("policy0/scaling_min_freq", "echo 1000000"))
        assertTrue("Custom governor restored", shell.wrote("scaling_governor", "echo walt"))
        assertEquals(TuningState(PowerTier.CUSTOM, customSide), store.state)
    }

    @Test
    fun `a preset clears every side control in storage`() = runTest {
        store.state = TuningState(
            PowerTier.CUSTOM,
            SideControlState(true, 69, true, true, 40, 30, true),
        )

        tuning.switchTo(PowerTier.BALANCED)

        val side = store.state.sideControls
        assertFalse(side.powerTargetEnabled || side.gpuLocked || side.primeCoreBoostLimited)
        assertEquals(0, side.gpuFloorPercent + side.cpuFloorPercent)
        assertEquals(PowerTier.BALANCED, store.state.tier)
    }

    @Test
    fun `a bound game with its own binding blocks the switch without saving it`() = runTest {
        givenCustomSetup()
        ownership.owner = ClockOwner.BoundGame(autoTdp = false)

        val outcome = tuning.switchTo(PowerTier.MAX)

        assertEquals(TuningOutcome.Blocked, outcome)
        assertEquals(TuningState(PowerTier.CUSTOM, customSide), store.state)
        assertTrue(caps.calls.isEmpty())
        assertTrue(shell.commands.isEmpty())
        assertEquals(0, ownership.capsWrittenCount)
    }

    @Test
    fun `a following-global AutoTDP session is released before the caps are written`() = runTest {
        ownership.owner = ClockOwner.GlobalAutoTdp

        tuning.switchTo(PowerTier.MAX)

        assertEquals(listOf("release", "caps"), events.filter { it == "release" || it == "caps" })
        assertEquals(1, ownership.capsWrittenCount)
    }

    @Test
    fun `a Power Target edit on Max switches to Custom and snapshots it`() = runTest {
        store.state = TuningState(PowerTier.MAX, SideControlState.CLEARED)

        tuning.edit(SideControlEdit.PowerTarget(enabled = true, percent = 69))

        val apply = caps.calls.single()
        assertEquals(PowerTargetMath.capsForPercent(policies, 69, cpuOnly = false), apply.values)
        assertTrue(apply.persistAsCustom)
        assertEquals(PowerTier.CUSTOM, store.state.tier)
        assertTrue(store.snapshot.powerTargetEnabled)
        assertEquals(69, store.snapshot.powerTargetPercent)
    }

    @Test
    fun `a Power Target edit while AutoTDP owns the clocks is saved but not written`() = runTest {
        ownership.owner = ClockOwner.BoundGame(autoTdp = true)

        val outcome = tuning.edit(SideControlEdit.PowerTarget(enabled = true, percent = 69))

        assertEquals(TuningOutcome.Deferred, outcome)
        assertTrue(caps.calls.isEmpty())
        assertTrue(shell.commands.isEmpty())
        assertEquals(69, store.state.sideControls.powerTargetPercent)
    }

    @Test
    fun `a failed Power Target re-apply fails the Custom restore`() = runTest {
        givenCustomSetup()
        val powerTargetCaps = PowerTargetMath.capsForPercent(policies, 69, cpuOnly = false)
        caps.failing = { it == powerTargetCaps }

        val outcome = tuning.switchTo(PowerTier.CUSTOM)

        assertEquals(TuningOutcome.Failed(FailureReason.WRITE_FAILED, "write failed"), outcome)
    }

    @Test
    fun `Custom with no saved setup fails but still switches the label`() = runTest {
        store.state = TuningState(PowerTier.MAX, SideControlState.CLEARED)

        val outcome = tuning.switchTo(PowerTier.CUSTOM)

        assertEquals(TuningOutcome.Failed(FailureReason.NO_SAVED_CUSTOM), outcome)
        assertEquals(PowerTier.CUSTOM, store.state.tier)
    }

    @Test
    fun `device commands run off the calling thread`() = runTest {
        givenCustomSetup()
        val caller = Thread.currentThread()
        val checkedShell = PrivilegedShell { command ->
            assertFalse("Device command ran on the caller thread", Thread.currentThread() === caller)
            shell.run(command)
        }
        val checkedTuning = GlobalTuning(store, caps, checkedShell, { ownership })

        checkedTuning.switchTo(PowerTier.MAX)
        checkedTuning.switchTo(PowerTier.CUSTOM)
        checkedTuning.edit(SideControlEdit.CpuFloor(50))
        checkedTuning.edit(SideControlEdit.GpuFloor(50))
        checkedTuning.edit(SideControlEdit.GpuLock(true))
        checkedTuning.edit(SideControlEdit.PrimeBoost(true))
        checkedTuning.edit(SideControlEdit.Governor(GovernorController.OPTIONS.first()))
        assertTrue(shell.commands.isNotEmpty())
    }
    @Test
    fun `Power Target slider and switch reassert the global GPU floor after caps`() = runTest {
        store.state = TuningState(PowerTier.CUSTOM, SideControlState.CLEARED.copy(gpuFloorPercent = 75))

        for (edit in listOf(
            SideControlEdit.PowerTarget(true, 65),
            SideControlEdit.PowerTarget(true, 65),
            SideControlEdit.PowerTargetCpuOnly(true),
        )) {
            shell.commands.clear()
            events.clear()
            tuning.edit(edit)
            assertTrue(shell.wrote("min_pwrlevel", "echo 1"))
            assertEquals("caps", events.first())
            assertEquals("shell", events.last())
        }
    }

    @Test
    fun `binding apply and periodic reapply inherit the global GPU floor`() = runTest {
        store.state = TuningState(PowerTier.CUSTOM, SideControlState.CLEARED.copy(gpuFloorPercent = 75))

        repeat(2) {
            shell.commands.clear()
            events.clear()
            tuning.applyBindingCaps(policies) {
                caps.applyCaps(policies, mapOf(gpu.id to 500_000), false, false)
            }
            assertTrue(shell.wrote("min_pwrlevel", "echo 1"))
            assertEquals("caps", events.first())
            assertEquals("shell", events.last())
        }
    }

    @Test
    fun `leaving a bound app restores the global floor after restoring caps`() = runTest {
        store.state = TuningState(PowerTier.CUSTOM, SideControlState.CLEARED.copy(gpuFloorPercent = 75))
        tuning.applyBindingCaps(policies, gpuFloorPercent = 0) {
            caps.applyCaps(policies, mapOf(gpu.id to 500_000), false, false)
        }
        assertTrue(shell.wrote("min_pwrlevel", "echo 2"))
        shell.commands.clear()
        events.clear()

        tuning.applyBindingCaps(policies) {
            caps.applyCaps(policies, mapOf(gpu.id to 800_000), false, false)
        }

        assertTrue(shell.wrote("min_pwrlevel", "echo 1"))
        assertEquals("caps", events.first())
        assertEquals("shell", events.last())
        assertEquals(75, store.state.sideControls.gpuFloorPercent)
    }

    @Test
    fun `a binding floor overrides the global GPU lock without changing global state`() = runTest {
        val global = SideControlState.CLEARED.copy(gpuLocked = true)
        store.state = TuningState(PowerTier.CUSTOM, global)

        tuning.applyBindingCaps(policies, gpuFloorPercent = 75) {
            caps.applyCaps(policies, mapOf(gpu.id to 500_000), false, false)
        }

        assertTrue(shell.wrote("min_pwrlevel", "echo 1"))
        assertFalse(shell.wrote("max_pwrlevel", "echo"))
        assertEquals(global, store.state.sideControls)
    }

    @Test
    fun `a binding GPU floor cannot exceed its cap`() = runTest {
        shell.gpuCapLevel = 2

        tuning.applyBindingCaps(policies, gpuFloorPercent = 75) {
            caps.applyCaps(policies, mapOf(gpu.id to 200_000), false, false)
        }

        assertTrue(shell.wrote("min_pwrlevel", "echo 2"))
        assertFalse(shell.wrote("min_pwrlevel", "echo 1"))
    }

    private fun policy(id: Int, freqs: List<Int>, path: String = "/sys/devices/system/cpu/cpufreq/policy$id") =
        CpuPolicyInfo(
            id = id,
            policyPath = path,
            scalingMaxPath = "$path/scaling_max_freq",
            currentMaxFreq = freqs.last(),
            selectableMaxFreq = freqs.last(),
            observedMaxFreq = freqs.last(),
            minFreq = freqs.first(),
            supportedFrequencies = freqs,
        )

    private class FakeShell(private val events: MutableList<String>) : PrivilegedShell {
        val commands = mutableListOf<String>()
        var gpuCapLevel = 0

        override fun run(command: String): String? {
            commands += command
            events += "shell"
            return when {
                command.contains("scaling_available_governors") -> "walt performance powersave"
                command.startsWith("cat") && command.contains("pwrlevel") -> gpuCapLevel.toString()
                else -> null
            }
        }

        fun wrote(node: String, write: String): Boolean =
            commands.any { cmd -> cmd.split("; ").any { it.contains(write) && it.contains(node) } }
    }

    private class InMemoryTuningStore : TuningStore {
        var state = TuningState(PowerTier.CUSTOM, SideControlState.CLEARED)
        var snapshot = CustomTuning()
        var custom: Map<Int, Int> = emptyMap()
        var last: Map<Int, Int> = emptyMap()

        override suspend fun state() = state
        override suspend fun writeState(state: TuningState) { this.state = state }
        override suspend fun customSnapshot() = snapshot
        override suspend fun writeCustomSnapshot(snapshot: CustomTuning) { this.snapshot = snapshot }
        override suspend fun customValues() = custom
        override suspend fun lastValues() = last
    }

    private data class CapsCall(val values: Map<Int, Int>, val isReset: Boolean, val persistAsCustom: Boolean)

    private class FakeCaps(
        private val store: InMemoryTuningStore,
        private val policies: List<CpuPolicyInfo>,
        private val events: MutableList<String>,
    ) : CapWriter {
        val calls = mutableListOf<CapsCall>()
        var failing: (Map<Int, Int>) -> Boolean = { false }
        override val isAvailable = true
        override fun policies() = policies

        override suspend fun applyCaps(
            policies: List<CpuPolicyInfo>,
            values: Map<Int, Int>,
            isReset: Boolean,
            persistAsCustom: Boolean,
        ): Result<PerformanceRepository.ApplyOutcome> {
            calls += CapsCall(values, isReset, persistAsCustom)
            events += "caps"
            if (failing(values)) return Result.failure(IllegalStateException("write failed"))
            store.last = values
            if (persistAsCustom) store.custom = values
            return Result.success(PerformanceRepository.ApplyOutcome(values, verificationPassed = true, commandOutput = null))
        }
    }

    private class FakeOwnership(private val events: MutableList<String>) : ClockOwnership, ClockSession {
        override var owner: ClockOwner = ClockOwner.Free
        var capsWrittenCount = 0

        override suspend fun <T> withOwnership(block: suspend ClockSession.() -> T): T = block()
        override suspend fun releaseGlobalAutoTdp() {
            events += "release"
            owner = ClockOwner.Free
        }
        override suspend fun capsWritten() { capsWrittenCount++ }
    }
}
