package com.kei.pulse.automation

import com.kei.pulse.data.FailureReason
import com.kei.pulse.data.PerformanceRepository
import android.app.Activity
import com.kei.pulse.data.TuningOutcome
import com.kei.pulse.model.AppSettings
import com.kei.pulse.model.PowerTier
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationCommandTest {
    private val enabled = AppSettings(automationEnabled = true, pulseEnabled = true)

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

    @Test fun tierNamesDispatchToTheExpectedTier() = runTest {
        val names = mapOf(
            "max" to PowerTier.MAX, "Max" to PowerTier.MAX, " balanced " to PowerTier.BALANCED,
            "power_saving" to PowerTier.POWER_SAVING, "Custom" to PowerTier.CUSTOM,
        )
        for ((name, tier) in names) {
            val target = Target()
            val command = parseAutomationCommand("com.kei.pulse.action.SET_TIER", mapOf("tier" to name))
            assertEquals("applied", dispatchAutomationCommand(enabled, command, target))
            assertEquals(listOf("tier=$tier"), target.calls)
        }
    }

    @Test fun disabledCommandsNeverReachTheTarget() = runTest {
        val commands = listOf(
            AutomationCommand.Query, AutomationCommand.SetTier(PowerTier.MAX),
            AutomationCommand.ApplyProfile("Game"), AutomationCommand.Invalid("unknown action"),
        )
        val gates = mapOf(
            AppSettings(automationEnabled = false, pulseEnabled = true) to "disabled: enable Allow automation broadcasts in Settings",
            AppSettings(automationEnabled = true, pulseEnabled = false) to "disabled: PULSE master switch is off",
        )
        for ((settings, expected) in gates) {
            val target = Target()
            for (command in commands) {
                assertEquals(expected, dispatchAutomationCommand(settings, command, target))
            }
            assertTrue(target.calls.isEmpty())
        }
    }

    @Test fun invalidInputsNeverDispatch() = runTest {
        val target = Target()
        val commands = listOf(
            parseAutomationCommand(null, emptyMap()),
            parseAutomationCommand("other", emptyMap()),
            parseAutomationCommand("com.kei.pulse.action.SET_TIER", emptyMap()),
            parseAutomationCommand("com.kei.pulse.action.SET_TIER", mapOf("tier" to "turbo")),
            parseAutomationCommand("com.kei.pulse.action.SET_TIER", mapOf("tier" to "AAA / Max")),
            parseAutomationCommand("com.kei.pulse.action.SET_TIER", mapOf("tier" to "power saving")),
            parseAutomationCommand("com.kei.pulse.action.APPLY_PROFILE", emptyMap()),
            parseAutomationCommand("com.kei.pulse.action.APPLY_PROFILE", mapOf("name" to " ")),
        )
        for (command in commands) {
            assertTrue(dispatchAutomationCommand(enabled, command, target).startsWith("invalid:"))
        }
        assertTrue(target.calls.isEmpty())
    }

    @Test fun profileNameIsPassedExactlyAndOutcomesArePreserved() = runTest {
        for (result in listOf("applied: profile=My Game", "blocked: bound game owns clocks",
            "deferred: AutoTDP owns clocks", "failed: profile not found: My Game")) {
            val target = Target().apply { writeResult = result }
            val command = parseAutomationCommand("com.kei.pulse.action.APPLY_PROFILE", mapOf("name" to "My Game"))
            assertEquals(result, dispatchAutomationCommand(enabled, command, target))
            assertEquals(listOf("profile=My Game"), target.calls)
        }
    }

    @Test fun queryReturnsDeviceReadbackText() = runTest {
        val target = Target()
        val command = parseAutomationCommand("com.kei.pulse.action.QUERY", emptyMap())
        assertEquals(
            "tier=Balanced; policy0.scaling_max_freq=2000000; gpu.max_pwrlevel=3; gpu.min_pwrlevel=13",
            dispatchAutomationCommand(enabled, command, target),
        )
        assertEquals(listOf("query"), target.calls)
    }

    @Test fun tuningOutcomesNeverHideOwnershipOrVerificationFailures() {
        assertEquals("blocked: bound game owns clocks", formatAutomationOutcome("tier=Max", TuningOutcome.Blocked))
        assertEquals("deferred: AutoTDP owns clocks", formatAutomationOutcome("tier=Max", TuningOutcome.Deferred))
        assertEquals("failed: WRITE_FAILED: denied", formatAutomationOutcome(
            "tier=Max", TuningOutcome.Failed(FailureReason.WRITE_FAILED, "denied"),
        ))
        assertEquals("failed: caps written but verification failed", formatAutomationOutcome(
            "tier=Max", TuningOutcome.Applied(caps = PerformanceRepository.ApplyOutcome(emptyMap(), false, null)),
        ))
        assertEquals("applied: tier=Max", formatAutomationOutcome("tier=Max", TuningOutcome.Applied()))
    }

    @Test fun onlyAppliedAndQueryResultsAreOk() {
        val ok = listOf("applied: tier=Max", "tier=Balanced; policy0.scaling_max_freq=1")
        val rejected = listOf(
            "disabled: PULSE master switch is off", "blocked: bound game owns clocks",
            "deferred: still applying", "failed: WRITE_FAILED", "invalid: name is required",
        )
        ok.forEach { assertEquals(Activity.RESULT_OK, automationResultCode(it)) }
        rejected.forEach { assertEquals(Activity.RESULT_FIRST_USER, automationResultCode(it)) }
    }

    @Test fun slowWorkReportsStillApplyingButIsNeverCancelled() = runTest {
        var finished = false
        val result = runWithinBudget(this, 8_000) {
            delay(20_000)
            finished = true
            "applied: tier=Max"
        }

        assertEquals(STILL_APPLYING, result)
        assertFalse(finished)
        delay(20_000)
        assertTrue(finished)
    }

    @Test fun fastWorkReturnsItsResult() = runTest {
        assertEquals("applied: tier=Max", runWithinBudget(this, 8_000) { "applied: tier=Max" })
    }
}
