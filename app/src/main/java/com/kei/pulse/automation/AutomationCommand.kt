package com.kei.pulse.automation

import android.app.Activity
import com.kei.pulse.data.TuningOutcome
import com.kei.pulse.model.AppSettings
import com.kei.pulse.model.PowerTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface AutomationCommand {
    data class SetTier(val tier: PowerTier) : AutomationCommand
    data class ApplyProfile(val name: String) : AutomationCommand
    data object Query : AutomationCommand
    data class Invalid(val message: String) : AutomationCommand
}

internal fun parseAutomationCommand(action: String?, extras: Map<String, String?>): AutomationCommand = when (action) {
    "com.kei.pulse.action.SET_TIER" -> {
        val name = extras["tier"]?.trim()
        PowerTier.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?.let(AutomationCommand::SetTier)
            ?: AutomationCommand.Invalid("tier must be max, balanced, power_saving, or custom")
    }
    "com.kei.pulse.action.APPLY_PROFILE" -> extras["name"]?.takeIf { it.isNotBlank() }
        ?.let(AutomationCommand::ApplyProfile) ?: AutomationCommand.Invalid("name is required")
    "com.kei.pulse.action.QUERY" -> AutomationCommand.Query
    else -> AutomationCommand.Invalid("unknown action")
}

internal interface AutomationTarget {
    suspend fun setTier(tier: PowerTier): String
    suspend fun applyProfile(name: String): String
    suspend fun query(): String
}

internal suspend fun dispatchAutomationCommand(
    settings: AppSettings,
    command: AutomationCommand,
    target: AutomationTarget,
): String {
    if (!settings.automationEnabled) return "disabled: enable Allow automation broadcasts in Settings"
    if (!settings.pulseEnabled) return "disabled: PULSE master switch is off"
    return when (command) {
        is AutomationCommand.Invalid -> "invalid: ${command.message}"
        is AutomationCommand.SetTier -> target.setTier(command.tier)
        is AutomationCommand.ApplyProfile -> target.applyProfile(command.name)
        AutomationCommand.Query -> target.query()
    }
}

internal fun formatAutomationOutcome(write: String, outcome: TuningOutcome): String = when (outcome) {
    TuningOutcome.Blocked -> "blocked: bound game owns clocks"
    TuningOutcome.Deferred -> "deferred: AutoTDP owns clocks"
    is TuningOutcome.Failed -> "failed: ${outcome.reason}${outcome.message?.let { ": $it" } ?: ""}"
    is TuningOutcome.Applied -> if (outcome.caps?.verificationPassed == false) {
        "failed: caps written but verification failed"
    } else {
        "applied: $write"
    }
}

private val rejectedPrefixes = listOf("disabled:", "blocked:", "deferred:", "failed:", "invalid:")

internal fun automationResultCode(result: String): Int =
    if (rejectedPrefixes.any(result::startsWith)) Activity.RESULT_FIRST_USER else Activity.RESULT_OK

internal const val STILL_APPLYING = "deferred: still applying"

internal suspend fun runWithinBudget(scope: CoroutineScope, budgetMs: Long, work: suspend () -> String): String {
    val pending = scope.async { work() }
    return withTimeoutOrNull(budgetMs) { pending.await() } ?: STILL_APPLYING
}
