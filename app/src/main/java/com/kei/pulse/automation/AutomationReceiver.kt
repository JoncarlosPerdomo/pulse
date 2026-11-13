package com.kei.pulse.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.kei.pulse.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val RECEIVER_BUDGET_MS = 8_000L

private val automationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val container = AppContainer(context.applicationContext)
        val command = parseAutomationCommand(
            intent.action,
            mapOf("tier" to intent.getStringExtra("tier"), "name" to intent.getStringExtra("name")),
        )
        automationScope.launch {
            try {
                val result = runWithinBudget(automationScope, RECEIVER_BUDGET_MS) {
                    runCatching {
                        val settings = container.settingsStorage.settings.first()
                        dispatchAutomationCommand(settings, command, AutomationTargetAdapter(container))
                    }.getOrElse { "failed: ${it.message ?: it.javaClass.simpleName}" }
                }
                pending.setResult(automationResultCode(result), result, Bundle().apply { putString("result", result) })
            } finally {
                pending.finish()
            }
        }
    }
}
