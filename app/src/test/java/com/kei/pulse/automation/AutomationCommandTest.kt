package com.kei.pulse.automation

import com.kei.pulse.data.FailureReason
import com.kei.pulse.data.PerformanceRepository
import com.kei.pulse.data.TuningOutcome
import com.kei.pulse.model.PowerTier
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationCommandTest {
    private class Target : AutomationTarget {
        val calls = mutableListOf<String>()
        var writeResult = "applied"
        override suspend fun setTier(tier: PowerTier): String {
            calls += "tier=$tier"
            return writeResult
        }
        override suspend fun applyProfile(name: String): String {
            calls += "profile=$name"
            return writeResult
        }
        override suspend fun query(): String {
            calls += "query"
            return "tier=Balanced; policy0.scaling_max_freq=2000000; gpu.max_pwrlevel=3; gpu.min_pwrlevel=13"
        }
    }

    @Test fun tierAliasesDispatchToTheExpectedTier() = runTest {
        val aliases = mapOf(
            "AAA" to PowerTier.MAX, "Max" to PowerTier.MAX, "AAA / Max" to PowerTier.MAX,
            " balanced " to PowerTier.BALANCED, "power_saving" to PowerTier.POWER_SAVING,
            "Power Saving" to PowerTier.POWER_SAVING, "Custom" to PowerTier.CUSTOM,
        )
        for ((alias, tier) in aliases) {
            val target = Target()
            val command = parseAutomationCommand("com.kei.pulse.action.SET_TIER", mapOf("tier" to alias))
            assertEquals("applied", dispatchAutomationCommand(true, command, target))
            assertEquals(listOf("tier=$tier"), target.calls)
        }
    }

    @Test fun disabledCommandsNeverReachTheTarget() = runTest {
        val target = Target()
        val commands = listOf(
            AutomationCommand.Query, AutomationCommand.SetTier(PowerTier.MAX),
            AutomationCommand.ApplyProfile("Game"), AutomationCommand.Invalid("unknown action"),
        )
        for (command in commands) {
            assertTrue(dispatchAutomationCommand(false, command, target).startsWith("disabled:"))
        }
        assertTrue(target.calls.isEmpty())
    }

    @Test fun invalidInputsNeverDispatch() = runTest {
        val target = Target()
        val commands = listOf(
            parseAutomationCommand(null, emptyMap()),
            parseAutomationCommand("other", emptyMap()),
            parseAutomationCommand("com.kei.pulse.action.SET_TIER", emptyMap()),
            parseAutomationCommand("com.kei.pulse.action.SET_TIER", mapOf("tier" to "turbo")),
            parseAutomationCommand("com.kei.pulse.action.APPLY_PROFILE", emptyMap()),
            parseAutomationCommand("com.kei.pulse.action.APPLY_PROFILE", mapOf("name" to " ")),
        )
        for (command in commands) {
            assertTrue(dispatchAutomationCommand(true, command, target).startsWith("invalid:"))
        }
        assertTrue(target.calls.isEmpty())
    }

    @Test fun profileNameIsPassedExactlyAndOutcomesArePreserved() = runTest {
        for (result in listOf("applied: profile=My Game", "blocked: bound game owns clocks",
            "deferred: AutoTDP owns clocks", "failed: profile not found: My Game")) {
            val target = Target().apply { writeResult = result }
            val command = parseAutomationCommand("com.kei.pulse.action.APPLY_PROFILE", mapOf("name" to "My Game"))
            assertEquals(result, dispatchAutomationCommand(true, command, target))
            assertEquals(listOf("profile=My Game"), target.calls)
        }
    }

    @Test fun queryReturnsDeviceReadbackText() = runTest {
        val target = Target()
        val command = parseAutomationCommand("com.kei.pulse.action.QUERY", emptyMap())
        assertEquals(
            "tier=Balanced; policy0.scaling_max_freq=2000000; gpu.max_pwrlevel=3; gpu.min_pwrlevel=13",
            dispatchAutomationCommand(true, command, target),
        )
        assertEquals(listOf("query"), target.calls)
    }

    @Test fun tuningOutcomesNeverHideOwnershipOrVerificationFailures() {
        assertEquals("blocked: bound game owns clocks", formatAutomationOutcome("tier=Max", TuningOutcome.Blocked))
        assertEquals("deferred: AutoTDP owns clocks", formatAutomationOutcome("tier=Max", TuningOutcome.Deferred))
        assertEquals("failed: WRITE_FAILED: denied", formatAutomationOutcome(
            "tier=Max", TuningOutcome.Failed(FailureReason.WRITE_FAILED, "denied"),
        ))
        assertEquals("failed: cap verification failed", formatAutomationOutcome(
            "tier=Max", TuningOutcome.Applied(caps = PerformanceRepository.ApplyOutcome(emptyMap(), false, null)),
        ))
        assertEquals("applied: tier=Max", formatAutomationOutcome("tier=Max", TuningOutcome.Applied()))
    }
}
