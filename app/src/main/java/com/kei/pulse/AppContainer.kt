package com.kei.pulse

import android.content.Context
import com.kei.pulse.data.BundledProfileProvider
import com.kei.pulse.data.CpuPolicyDetector
import com.kei.pulse.data.DataStoreTuningStore
import com.kei.pulse.data.GlobalTuning
import com.kei.pulse.data.GovernorController
import com.kei.pulse.data.GpuFreqDetector
import com.kei.pulse.data.PerAppConfigStorage
import com.kei.pulse.data.PerformanceRepository
import com.kei.pulse.data.PrivilegedSysfsReader
import com.kei.pulse.data.ProfileStorage
import com.kei.pulse.data.SettingsStorage
import com.kei.pulse.model.ClockOwnership
import com.kei.pulse.root.PServerShell
import com.kei.pulse.root.PerformanceCommandBuilder
import com.kei.pulse.root.PServerSysfsReader
import com.kei.pulse.root.RootCommandRunner
import kotlinx.coroutines.sync.Mutex

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsStorage: SettingsStorage by lazy {
        SettingsStorage(appContext)
    }

    val perAppConfigStorage: PerAppConfigStorage by lazy {
        PerAppConfigStorage(appContext)
    }

    val sysfsReader: PrivilegedSysfsReader by lazy {
        PServerSysfsReader(appContext)
    }

    private val profileStorage: ProfileStorage by lazy {
        ProfileStorage(appContext)
    }

    val repository: PerformanceRepository by lazy {
        PerformanceRepository(
            detector = CpuPolicyDetector(
                privilegedReader = sysfsReader,
                gpuDetector = GpuFreqDetector(
                    privilegedReader = sysfsReader,
                ),
            ),
            bundledProfileProvider = BundledProfileProvider(appContext),
            profileStorage = profileStorage,
            commandBuilder = PerformanceCommandBuilder(),
            rootCommandRunner = RootCommandRunner(appContext),
            governorController = GovernorController(),
        )
    }

    val globalTuning: GlobalTuning by lazy {
        GlobalTuning(
            store = DataStoreTuningStore(settingsStorage, profileStorage),
            caps = repository,
            shell = PServerShell,
            ownership = { activeClockOwnership ?: ClockOwnership.FREE },
            mutex = globalTuningMutex,
        )
    }

    companion object {
        /** Set by the foreground-app watcher while it runs; AppContainer instances are per-caller, so it lives here. */
        @Volatile
        var activeClockOwnership: ClockOwnership? = null

        private val globalTuningMutex = Mutex()
    }
}
