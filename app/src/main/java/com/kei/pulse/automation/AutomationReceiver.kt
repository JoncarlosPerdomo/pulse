package com.kei.pulse.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.kei.pulse.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = runCatching {
                    val container = AppContainer(appContext)
                    val enabled = container.settingsStorage.automationEnabled.first()
                    val command = parseAutomationCommand(intent.action, mapOf(
                        "tier" to intent.getStringExtra("tier"),
                        "name" to intent.getStringExtra("name"),
                    ))
                    if (enabled && !container.settingsStorage.settings.first().pulseEnabled) {
                        "disabled: PULSE master switch is off"
                    } else {
                        dispatchAutomationCommand(enabled, command, AutomationTargetAdapter(container))
                    }
                }.getOrElse { "failed: ${it.message ?: it.javaClass.simpleName}" }
                pending.setResult(0, result, Bundle().apply { putString("result", result) })
            } finally {
                pending.finish()
            }
        }
    }
}
