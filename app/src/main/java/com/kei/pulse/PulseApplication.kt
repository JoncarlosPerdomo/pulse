package com.kei.pulse

import android.app.Application
import android.util.Log
import com.kei.pulse.root.CpuClockProtection
import com.kei.pulse.root.PServerShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PulseApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            runCatching {
                check(PServerShell.run(CpuClockProtection.script(""))?.trim() == CpuClockProtection.SUCCESS)
            }.onFailure { Log.e("PulseCpuProtection", "CPU permission recovery failed", it) }
        }
    }
}
