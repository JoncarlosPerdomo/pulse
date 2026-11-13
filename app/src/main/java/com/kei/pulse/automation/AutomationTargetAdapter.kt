package com.kei.pulse.automation

import android.util.Log
import com.kei.pulse.AppContainer
import com.kei.pulse.data.TuningOutcome
import com.kei.pulse.model.PowerTier
import com.kei.pulse.model.ProfileSource
import kotlinx.coroutines.flow.first

internal class AutomationTargetAdapter(private val container: AppContainer) : AutomationTarget {
    override suspend fun setTier(tier: PowerTier): String = report(
        "tier=${tier.label}", container.globalTuning.switchTo(tier),
    )

    override suspend fun applyProfile(name: String): String {
        val repository = container.repository
        val matches = repository.observeState().first().displayProfiles.filter {
            it.source != ProfileSource.VIRTUAL && it.name == name
        }
        if (matches.isEmpty()) return "failed: profile not found: $name"
        if (matches.size != 1) return "failed: ambiguous profile name: $name"
        val profile = matches.single()
        val outcome = container.globalTuning.applyProfile(profile.id)
        if (outcome is TuningOutcome.Applied) repository.selectProfile(profile.id)
        return report("profile=$name caps_khz=${profile.maxFrequencies}", outcome)
    }

    override suspend fun query(): String {
        val policies = container.repository.currentPolicies()
        if (policies.isEmpty()) return "failed: no policies detected"
        val gpu = policies.firstOrNull { it.isGpu }
        val nodes = policies.filterNot { it.isGpu }.map { "policy${it.id}.scaling_max_freq" to "${it.policyPath}/scaling_max_freq" } +
            listOf("max_pwrlevel", "min_pwrlevel").mapNotNull { node -> gpu?.let { "gpu.$node" to "${it.policyPath}/$node" } }
        val readback = container.sysfsReader.readAll(nodes.map { it.second })
        val tier = container.settingsStorage.settings.first().activeTierLabel
        return "tier=$tier; " + nodes.joinToString("; ") { (key, path) ->
            "$key=${readback[path]?.trim()?.toIntOrNull() ?: "unavailable"}"
        }
    }

    private fun report(write: String, outcome: TuningOutcome): String {
        val details = when (outcome) {
            is TuningOutcome.Applied -> "caps_khz=${outcome.caps?.actualValues} governor=${outcome.governor} gpuLockLevel=${outcome.gpuLockLevel}"
            is TuningOutcome.Failed -> "failed=${outcome.reason}"
            TuningOutcome.Blocked, TuningOutcome.Deferred -> null
        }
        if (details != null) Log.i("PulseAutomation", "write $write $details".replace('\n', ' ').replace('\r', ' '))
        return formatAutomationOutcome(write, outcome)
    }
}
