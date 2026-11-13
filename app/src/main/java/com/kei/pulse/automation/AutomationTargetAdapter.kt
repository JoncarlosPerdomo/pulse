package com.kei.pulse.automation

import android.util.Log
import com.kei.pulse.AppContainer
import com.kei.pulse.data.GpuFloorController
import com.kei.pulse.data.TuningOutcome
import com.kei.pulse.model.ClockOwner
import com.kei.pulse.model.ClockOwnership
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
        val ownership = AppContainer.activeClockOwnership ?: ClockOwnership.FREE
        return ownership.withOwnership {
            if (owner is ClockOwner.BoundGame) return@withOwnership "blocked: bound game owns clocks"
            if (owner == ClockOwner.GlobalAutoTdp) releaseGlobalAutoTdp()
            val result = repository.applyDisplayProfileById(matches.single().id)
            val applied = result.getOrElse {
                Log.i("PulseAutomation", "write profile=$name failed=${it.message}")
                return@withOwnership "failed: ${it.message}"
            }
            val settings = container.settingsStorage.settings.first()
            var lockFailed = false
            if (settings.gpuLocked) {
                val gpu = repository.currentPolicies().firstOrNull { it.isGpu }
                val level = gpu?.let { GpuFloorController().lockToCurrentCap(it.policyPath) }
                Log.i("PulseAutomation", "write gpu lock min_pwrlevel=max_pwrlevel readback=$level")
                lockFailed = level == null
            }
            capsWritten()
            container.repository.selectProfile(matches.single().id)
            val outcome = report("profile=$name caps_khz=${matches.single().maxFrequencies}", TuningOutcome.Applied(caps = applied))
            if (lockFailed) "failed: GPU lock readback failed" else outcome
        }
    }

    override suspend fun query(): String {
        val policies = container.repository.currentPolicies()
        if (policies.isEmpty()) return "failed: no policies detected"
        val cpuPaths = policies.filterNot { it.isGpu }.associate {
            "policy${it.id}.scaling_max_freq" to "${it.policyPath}/scaling_max_freq"
        }
        val gpu = policies.firstOrNull { it.isGpu }
        val paths = cpuPaths + listOf("max_pwrlevel", "min_pwrlevel").associate { node ->
            "gpu.$node" to gpu?.let { "${it.policyPath}/$node" }
        }
        val readback = container.sysfsReader.readAll(paths.values.filterNotNull())
        val tier = container.settingsStorage.settings.first().activeTierLabel
        return "tier=$tier; " + paths.entries.joinToString("; ") { (key, path) ->
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
