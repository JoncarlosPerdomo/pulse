package com.kei.pulse.automation

import com.kei.pulse.data.TuningOutcome
import com.kei.pulse.model.PowerTier

internal sealed interface AutomationCommand {
    data class SetTier(val tier: PowerTier) : AutomationCommand
    data class ApplyProfile(val name: String) : AutomationCommand
    data object Query : AutomationCommand
    data class Invalid(val message: String) : AutomationCommand
}

internal fun parseAutomationCommand(action: String?, extras: Map<String, String?>): AutomationCommand = when (action) {
    "com.kei.pulse.action.SET_TIER" -> {
        val tier = when (extras["tier"]?.trim()?.lowercase()) {
            "max", "aaa", "aaa/max", "aaa / max" -> PowerTier.MAX
            "balanced" -> PowerTier.BALANCED
            "power_saving", "power saving" -> PowerTier.POWER_SAVING
            "custom" -> PowerTier.CUSTOM
            else -> null
        }
        tier?.let(AutomationCommand::SetTier)
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
    enabled: Boolean,
    command: AutomationCommand,
    target: AutomationTarget,
): String {
    if (!enabled) return "disabled: enable Allow automation broadcasts in Settings"
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
        "failed: cap verification failed"
    } else {
        "applied: $write"
    }
}
