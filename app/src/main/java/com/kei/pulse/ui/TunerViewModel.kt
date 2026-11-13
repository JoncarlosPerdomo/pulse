package com.kei.pulse.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kei.pulse.data.DisplayController
import com.kei.pulse.data.GovernorController
import com.kei.pulse.data.GovernorOption
import com.kei.pulse.data.FailureReason
import com.kei.pulse.data.GlobalTuning
import com.kei.pulse.data.SideControlEdit
import com.kei.pulse.data.TuningOutcome
import com.kei.pulse.data.RefreshRateController
import com.kei.pulse.data.TelemetryReader
import com.kei.pulse.data.TelemetrySnapshot
import com.kei.pulse.data.FanController
import com.kei.pulse.data.FanCurveController
import com.kei.pulse.model.FanCurve
import com.kei.pulse.model.FanCalibration
import com.kei.pulse.model.FanCalibrationSample
import kotlinx.coroutines.delay
import com.kei.pulse.data.PerAppConfigStorage
import com.kei.pulse.data.PerformanceRepository
import com.kei.pulse.data.PowerEstimator
import com.kei.pulse.data.SettingsStorage
import com.kei.pulse.model.PerAppConfig
import kotlinx.coroutines.flow.flowOf
import com.kei.pulse.model.AppColorSource
import com.kei.pulse.model.AppSettings
import com.kei.pulse.model.AutoTdpBias
import com.kei.pulse.model.ClockOwner
import com.kei.pulse.model.CpuPolicyInfo
import com.kei.pulse.model.PerformanceProfile
import com.kei.pulse.model.PowerTier
import com.kei.pulse.model.ProfileStateResolver
import com.kei.pulse.model.ProfileSource
import com.kei.pulse.model.TileInteractionBehavior
import com.kei.pulse.model.TunerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.abs

class TunerViewModel(
    private val repository: PerformanceRepository,
    private val settingsStorage: SettingsStorage,
    private val telemetryReader: TelemetryReader,
    private val globalTuning: GlobalTuning,
    private val perAppConfigStorage: PerAppConfigStorage? = null,
) : ViewModel() {

    private val edits = MutableStateFlow<Map<Int, Int>>(emptyMap())
    private val transientMessage = MutableStateFlow<String?>(null)
    private val transientError = MutableStateFlow<String?>(null)

    val state: StateFlow<TunerState> = combine(
        repository.observeState(),
        edits,
        transientMessage,
        transientError,
    ) { repoState, localEdits, message, error ->
        ProfileStateResolver.resolve(
            repoState.copy(
                statusMessage = message,
                errorMessage = error,
            ),
            currentValues = repoState.currentValues + localEdits,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TunerState(),
    )

    val settings: StateFlow<AppSettings> = settingsStorage.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings(),
    )

    val perAppEnabled: StateFlow<Boolean> = (perAppConfigStorage?.enabled ?: flowOf(false))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val perAppConfigs: StateFlow<List<PerAppConfig>> =
        (perAppConfigStorage?.configs ?: flowOf(emptyList()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val perAppSwitchNotices: StateFlow<Boolean> = (perAppConfigStorage?.switchNotices ?: flowOf(true))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val perAppBatteryWh: StateFlow<Float> = (perAppConfigStorage?.batteryCapacityWh ?: flowOf(0f))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0f)

    /** Packages AutoTDP has learned a warm-start operating point for — drives the per-app "tuned" badge. */
    val autoTdpLearnedPackages: StateFlow<Set<String>> = settingsStorage.autoTdpLearnedPackages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun setPerAppEnabled(enabled: Boolean, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            perAppConfigStorage?.persistEnabled(enabled)
            onSaved()
        }
    }

    fun setPerAppSwitchNotices(enabled: Boolean) {
        viewModelScope.launch {
            perAppConfigStorage?.persistSwitchNotices(enabled)
        }
    }

    fun savePerAppConfig(config: PerAppConfig, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            perAppConfigStorage?.saveConfig(config)
            // A saved per-app binding must take effect: per-app always overrides the global mode for that
            // app, so enable the watcher here (otherwise the binding sits inert and the global Custom
            // baseline "wins"). No-op when per-app is already on.
            perAppConfigStorage?.persistEnabled(true)
            onSaved()
        }
    }

    fun removePerAppConfig(packageName: String) {
        viewModelScope.launch {
            perAppConfigStorage?.removeConfig(packageName)
        }
    }

    // Nominal all-out peak (W), self-calibrated from real measured draw at full load. 0 = not yet learned.
    private val _learnedPeakW = MutableStateFlow(0f)

    /**
     * Estimated peak system draw (W) at the current ceilings. Scaled by the self-calibrated nominal,
     * and shaped by AutoTDP's learned per-SoC CPU/GPU power split once it's confident (else the fixed
     * heuristic in [PowerEstimator]).
     */
    val estimatedPeakW: StateFlow<Float?> = combine(state, _learnedPeakW, settingsStorage.powerModel) { s, learned, model ->
        val nominal = if (learned > 1f) learned else PowerEstimator.DEFAULT_PEAK_W
        val relIndex = model?.takeIf { it.hasSplit() }?.relativeIndex(s.policies, s.currentValues)
            ?: PowerEstimator.relativeIndex(s.policies, s.currentValues)
        relIndex?.let { nominal * it }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // --- System controls (fan + resolution) -------------------------------------------
    private val fanController = FanController()
    private val displayController = DisplayController()
    private val governorController = GovernorController()
    private val refreshRateController = RefreshRateController()

    private val _governor = MutableStateFlow<String?>(null)
    val governor: StateFlow<String?> = _governor
    private val _refreshRate = MutableStateFlow<Int?>(null)
    val refreshRate: StateFlow<Int?> = _refreshRate

    private fun <T> setting(pick: (AppSettings) -> T): StateFlow<T> =
        settings.map(pick).distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), pick(AppSettings()))

    val gpuFloorPercent: StateFlow<Int> = setting { it.gpuFloorPercent }
    val cpuFloorPercent: StateFlow<Int> = setting { it.cpuFloorPercent }
    val powerTargetEnabled: StateFlow<Boolean> = setting { it.powerTargetEnabled }
    val powerTargetPercent: StateFlow<Int> = setting { it.powerTargetPercent }
    val powerTargetCpuOnly: StateFlow<Boolean> = setting { it.powerTargetCpuOnly }
    val gpuLocked: StateFlow<Boolean> = setting { it.gpuLocked }
    val primeCoreBoostLimited: StateFlow<Boolean> = setting { it.primeCoreBoostLimited }

    /** The persisted Tier, shown as Custom while a named display profile is the last thing applied. */
    val activeTier: StateFlow<PowerTier> = combine(settings, state) { s, snap ->
        val namedProfileApplied = snap.lastAppliedDisplayProfileId
            ?.let { it != ProfileStateResolver.MANUAL_PROFILE_ID } == true
        if (namedProfileApplied) PowerTier.CUSTOM else tierOf(s)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PowerTier.CUSTOM)

    private fun tierOf(s: AppSettings): PowerTier =
        PowerTier.entries.firstOrNull { it.label == s.activeTierLabel } ?: PowerTier.CUSTOM

    fun setCpuFloorPercent(percent: Int) {
        viewModelScope.launch {
            report(
                globalTuning.edit(SideControlEdit.CpuFloor(percent)),
                applied = if (percent <= 0) "CPU floor cleared" else "CPU floor set to ~$percent%",
                failed = "Couldn't set CPU floor",
            )
        }
    }

    private val _autoTdpDefault = MutableStateFlow(false)
    val autoTdpDefault: StateFlow<Boolean> = _autoTdpDefault

    private val _autoTdpFpsTarget = MutableStateFlow(60)
    val autoTdpFpsTarget: StateFlow<Int> = _autoTdpFpsTarget

    /** AutoTDP FPS-target options for this device's SoC (Odin: 30/40/60/90/120; 8 Gen 2: 60/90/120). */
    val autoTdpFpsOptions: List<Int> = PerAppConfig.fpsTargetsFor(repository.socModel())

    /** Whether to show per-mode watt caps on the AutoTDP chips — only the Odin enforces them. */
    val autoTdpShowWattCaps: Boolean =
        com.kei.pulse.data.AutoTuneController.appliesOdinPowerTuning(repository.socModel())

    private val _autoTdpAggressivePark = MutableStateFlow(true)
    val autoTdpAggressivePark: StateFlow<Boolean> = _autoTdpAggressivePark

    private val _autoTdpBias = MutableStateFlow(AutoTdpBias.EFFICIENT)
    val autoTdpBias: StateFlow<AutoTdpBias> = _autoTdpBias

    /**
     * Master switch. [onSaved] runs after persist and should (re)start the watcher: ON resumes management;
     * OFF makes the service hand everything back to manufacturer stock and stop itself (the pre-uninstall
     * "system is in control" state). Starting on OFF is intentional — it's how the revert gets a root context.
     */
    fun setPulseEnabled(enabled: Boolean, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            settingsStorage.persistPulseEnabled(enabled)
            onSaved()
        }
    }

    /** Toggle AutoTDP as the global default. [onSaved] runs after persist (to start/stop the watcher). */
    fun setAutoTdpDefault(enabled: Boolean, onSaved: () -> Unit = {}) {
        _autoTdpDefault.value = enabled
        viewModelScope.launch {
            settingsStorage.persistAutoTdpDefaultEnabled(enabled)
            // AutoTDP manages clocks per-game; the baseline (home/PULSE) must NOT keep a previous mode's
            // pinned values. Releasing here fixes Custom→AutoTDP leaving scaling_max_freq chmod-444-locked
            // (CPU stuck at the Custom cap, e.g. 3.28 GHz, with the "current values" readout frozen).
            if (enabled) {
                releaseManualCapsToStock()
            } else {
                reportSwitch(tierOf(settingsStorage.settings.first()), globalTuning.reapply())
            }
            onSaved()
        }
    }

    /** Set the RGB joystick-LED mode. [onSaved] runs after persist (to start the watcher so it engages). */
    fun setRgbMode(mode: com.kei.pulse.model.RgbMode, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            settingsStorage.persistRgbMode(mode)
            onSaved()
        }
    }

    /** Manual RGB: persist one stick's (or both, when target is BOTH) color + brightness. */
    fun setRgbManualStick(
        stick: com.kei.pulse.model.RgbStick,
        color: Int,
        brightness: Float,
        onSaved: () -> Unit = {},
    ) {
        viewModelScope.launch {
            settingsStorage.persistRgbManualStick(stick, color, brightness)
            onSaved()
        }
    }

    /** Manual RGB: which stick the controls edit. */
    fun setRgbManualTarget(target: com.kei.pulse.model.RgbStick) {
        viewModelScope.launch { settingsStorage.persistRgbManualTarget(target) }
    }

    /** Reopen every cluster + GPU to full and restore stock writability (unlock manual `chmod 444` caps). */
    private suspend fun releaseManualCapsToStock() {
        val policies = state.value.policies
        if (policies.isEmpty()) return
        val full = policies.associate { it.id to it.selectableMaxFreq }
        repository.applyValues(
            policies = policies,
            selectedValues = full,
            isReset = true,
            appliedDisplayProfileId = ProfileStateResolver.MANUAL_PROFILE_ID,
        )
    }

    /** Set the global default AutoTDP FPS target (per-app bindings can override it). */
    fun setAutoTdpFpsTarget(target: Int) {
        _autoTdpFpsTarget.value = target
        viewModelScope.launch { settingsStorage.persistAutoTdpFpsTarget(target) }
    }

    /** Toggle AutoTDP aggressive park (offline the prime cores when they aren't the limiter). */
    fun setAutoTdpAggressivePark(enabled: Boolean) {
        _autoTdpAggressivePark.value = enabled
        viewModelScope.launch { settingsStorage.persistAutoTdpAggressivePark(enabled) }
    }

    /** Set the AutoTDP efficiency↔smoothness lean (global default). Takes effect on the next AutoTDP session. */
    fun setAutoTdpBias(bias: AutoTdpBias) {
        _autoTdpBias.value = bias
        viewModelScope.launch { settingsStorage.persistAutoTdpBias(bias) }
    }

    fun setPrimeCoreBoostLimited(limited: Boolean) {
        viewModelScope.launch {
            report(
                globalTuning.edit(SideControlEdit.PrimeBoost(limited)),
                applied = if (limited) "Prime boost limited" else "Prime boost restored",
                failed = "No Prime core found",
            )
        }
    }

    fun setPowerTargetCpuOnly(enabled: Boolean) {
        viewModelScope.launch {
            report(
                globalTuning.edit(SideControlEdit.PowerTargetCpuOnly(enabled)),
                applied = powerTargetMessage(),
                failed = "Failed to set power target",
            )
        }
    }

    fun setGpuLocked(locked: Boolean) {
        viewModelScope.launch {
            val outcome = globalTuning.edit(SideControlEdit.GpuLock(locked))
            val pinned = (outcome as? TuningOutcome.Applied)?.gpuLockLevel
            val freqs = state.value.policies.firstOrNull { it.isGpu }?.supportedFrequencies.orEmpty()
            val message = when {
                !locked -> "GPU unlocked"
                pinned == null -> "Couldn't read the GPU clock to lock"
                else -> freqs.getOrNull(freqs.size - 1 - pinned)?.div(1000)
                    ?.let { "GPU locked at $it MHz" } ?: "GPU locked (level $pinned)"
            }
            report(outcome, applied = message, failed = "No GPU detected")
        }
    }

    init {
        viewModelScope.launch {
            val persisted = settingsStorage.settings.first()
            _learnedPeakW.value = persisted.learnedPeakW
            _autoTdpDefault.value = persisted.autoTdpDefaultEnabled
            _autoTdpFpsTarget.value = persisted.autoTdpFpsTarget
            _autoTdpAggressivePark.value = persisted.autoTdpAggressivePark
            _autoTdpBias.value = persisted.autoTdpBias
        }
        // The first system read can fire before the CPU policies are loaded, leaving the
        // governor null (no chip selected). Wait for policies, then read live controls.
        viewModelScope.launch {
            state.first { snap -> snap.policies.any { !it.isGpu } }
            refreshSystemControls()
        }
        // Capture full-battery energy once so the per-app page can estimate battery life.
        if (perAppConfigStorage != null) {
            viewModelScope.launch {
                withContext(Dispatchers.IO) { com.kei.pulse.data.BatteryReader.capacityWh() }
                    ?.let { perAppConfigStorage.persistBatteryCapacityWh(it) }
            }
        }
        // Keep the settings-backed UI state in sync with the persisted settings, so a change made ELSEWHERE
        // (the QS tile, and now the Quick Access bar) reflects in the app UI without a restart. Without this,
        // turning AutoTDP off elsewhere left the tiers locked; the bar's global fps edit showed stale in the
        // app (the "set 30 on the bar, app still says 60" bug — the init block seeds these ONCE). All fields
        // read here are declared ABOVE the init block (the Main.immediate first-emission NPE gotcha). The
        // diff-guards also keep a persist echo of the VM's own setter from re-firing anything.
        viewModelScope.launch {
            settingsStorage.settings.collect { s ->
                if (_autoTdpDefault.value != s.autoTdpDefaultEnabled) {
                    _autoTdpDefault.value = s.autoTdpDefaultEnabled
                }
                if (_autoTdpFpsTarget.value != s.autoTdpFpsTarget) {
                    _autoTdpFpsTarget.value = s.autoTdpFpsTarget
                }
                if (_autoTdpBias.value != s.autoTdpBias) {
                    _autoTdpBias.value = s.autoTdpBias
                }
                if (_autoTdpAggressivePark.value != s.autoTdpAggressivePark) {
                    _autoTdpAggressivePark.value = s.autoTdpAggressivePark
                }
            }
        }
    }

    private fun powerTargetMessage(): String? =
        settings.value.takeIf { it.powerTargetEnabled }?.let { "Power target set to ${it.powerTargetPercent}%" }

    private fun report(outcome: TuningOutcome, applied: String?, failed: String) {
        when (outcome) {
            is TuningOutcome.Applied -> {
                outcome.governor?.let { _governor.value = it }
                val caps = outcome.caps
                transientMessage.value = if (caps != null && !caps.verificationPassed) {
                    buildVerificationFailureMessage(state.value, caps.actualValues, caps.commandOutput)
                } else {
                    applied ?: transientMessage.value
                }
                transientError.value = null
            }
            TuningOutcome.Deferred -> {
                transientMessage.value = "Saved — a game owns the clocks right now"
                transientError.value = null
            }
            TuningOutcome.Blocked -> transientError.value = "A game owns the clocks — Tier not changed"
            is TuningOutcome.Failed -> transientError.value = outcome.message ?: failed
        }
    }

    private fun reportSwitch(tier: PowerTier, outcome: TuningOutcome) {
        if (tier == PowerTier.CUSTOM && outcome is TuningOutcome.Failed &&
            outcome.reason == FailureReason.NO_SAVED_CUSTOM
        ) {
            return
        }
        val applied = if (tier == PowerTier.CUSTOM) "Restored Custom" else "Applied ${tier.label}"
        report(outcome, applied = applied, failed = "Failed to apply ${tier.label}")
    }

    fun setPowerTargetEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val percent = settings.value.powerTargetPercent
            report(
                globalTuning.edit(SideControlEdit.PowerTarget(enabled, percent)),
                applied = if (enabled) "Power target set to $percent%" else null,
                failed = "Failed to set power target",
            )
        }
    }

    fun setPowerTargetPercent(percent: Int) {
        viewModelScope.launch {
            val enabled = settings.value.powerTargetEnabled
            report(
                globalTuning.edit(SideControlEdit.PowerTarget(enabled, percent)),
                applied = if (enabled) "Power target set to $percent%" else null,
                failed = "Failed to set power target",
            )
        }
    }

    private val _fanMode = MutableStateFlow<Int?>(null)
    val fanMode: StateFlow<Int?> = _fanMode

    /** True only on the Odin 3 whose firmware exposes the writable custom-fan PWM node — gates the Custom
     *  chip + curve editor so Thor/RP6 never see a dead option. Resolved off the main thread on first refresh. */
    private val _customFanSupported = MutableStateFlow(false)
    val customFanSupported: StateFlow<Boolean> = _customFanSupported

    /** True while the autocalibrate fan sweep is running (drives the editor's progress UI + disables re-runs). */
    private val _fanCalibrating = MutableStateFlow(false)
    val fanCalibrating: StateFlow<Boolean> = _fanCalibrating

    private val _nativeDisplay = MutableStateFlow<DisplayController.DisplaySpec?>(null)
    val nativeDisplay: StateFlow<DisplayController.DisplaySpec?> = _nativeDisplay

    private val _resolutionScale = MutableStateFlow(100)
    val resolutionScale: StateFlow<Int> = _resolutionScale

    fun refreshSystemControls() {
        viewModelScope.launch {
            data class Snap(
                val mode: Int?,
                val native: DisplayController.DisplaySpec?,
                val gov: String?,
                val rr: Int?,
                val customFan: Boolean,
            )
            val snap = withContext(Dispatchers.IO) {
                Snap(
                    mode = fanController.readMode(),
                    native = displayController.readNative(),
                    gov = state.value.policies.firstOrNull { !it.isGpu }
                        ?.let { governorController.readGovernor(it) },
                    rr = refreshRateController.readPeak(),
                    customFan = FanController.customFanAvailable(),
                )
            }
            // Custom never writes a vendor fan_mode, so the readback can't show it — reflect the persisted
            // Custom selection directly (Odin only) so the chip stays selected and the editor survives a
            // relaunch. Everywhere else, trust the live vendor readback.
            _customFanSupported.value = snap.customFan
            _fanMode.value =
                if (snap.customFan && settings.value.managedFanMode == FanController.CUSTOM) {
                    FanController.CUSTOM
                } else {
                    snap.mode
                }
            if (snap.native != null) _nativeDisplay.value = snap.native
            if (snap.gov != null) _governor.value = snap.gov
            if (snap.rr != null) _refreshRate.value = snap.rr
        }
    }

    fun setGovernor(option: GovernorOption) {
        viewModelScope.launch {
            report(globalTuning.edit(SideControlEdit.Governor(option)), applied = "CPU governor: ${option.label}", failed = "Couldn't set governor")
        }
    }

    fun setRefreshRate(hz: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { refreshRateController.setRate(hz) }
            _refreshRate.value = hz
            transientMessage.value = "Refresh rate set to ${hz}Hz"
            transientError.value = null
        }
    }

    fun setGpuFloorPercent(percent: Int) {
        viewModelScope.launch {
            report(
                globalTuning.edit(SideControlEdit.GpuFloor(percent)),
                applied = if (percent <= 0) "GPU floor cleared" else "GPU floor set to ~$percent%",
                failed = "No GPU detected",
            )
        }
    }

    private val _tuningReadback = MutableStateFlow(TuningReadback())
    val tuningReadback: StateFlow<TuningReadback> = _tuningReadback

    suspend fun readTelemetry(): TelemetrySnapshot {
        val snap = withContext(Dispatchers.IO) { telemetryReader.read(state.value.policies) }
        _tuningReadback.value = TuningReadback(
            expectedCaps = globalTuning.expectedCaps(state.value.policies),
            gpuMinLevel = snap.gpuMinLevel,
            gpuMaxLevel = snap.gpuMaxLevel,
            owner = globalTuning.clockOwner(),
        )
        updatePeakCalibration(snap)
        return snap
    }

    /**
     * Learn the device's true peak from reality: when the GPU is near full load, the measured
     * draw divided by the current relative-index implies the all-out nominal. EMA toward it so the
     * "Est peak" figure converges to this device instead of staying on the hard-coded constant.
     */
    private fun updatePeakCalibration(snap: TelemetrySnapshot) {
        // Charger current is not system draw — calibrating while plugged in corrupts the model.
        if (!snap.isDischarging) return
        val drawW = snap.batteryDrawW ?: return
        val busy = snap.gpuBusyPercent ?: return
        if (busy < 85 || drawW <= 0f) return
        val rel = PowerEstimator.relativeIndex(state.value.policies, state.value.currentValues) ?: return
        if (rel < 0.2f) return
        val impliedNominal = (drawW / rel).coerceIn(6f, 40f)
        val prior = _learnedPeakW.value.takeIf { it > 1f } ?: PowerEstimator.DEFAULT_PEAK_W
        val updated = prior * 0.85f + impliedNominal * 0.15f
        _learnedPeakW.value = updated
        viewModelScope.launch { settingsStorage.persistLearnedPeakW(updated) }
    }

    fun setFanMode(mode: Int, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { fanController.setMode(mode) }
            // Custom drives the PWM curve and never writes a vendor fan_mode, so reflect the chosen mode
            // directly — reading back the live value would still show Smart/etc. and the chip wouldn't stick.
            _fanMode.value = if (mode == FanController.CUSTOM) {
                mode
            } else {
                withContext(Dispatchers.IO) { fanController.readMode() }
            }
            // Remember it so the watcher re-asserts it against the system Fan tile; onSaved starts that watcher.
            settingsStorage.persistManagedFanMode(mode)
            onSaved()
            transientMessage.value = if (ok) {
                "Fan set to ${FanController.labelFor(mode)}"
            } else {
                "Couldn't change fan mode"
            }
            transientError.value = null
        }
    }

    /** Persist the user's edited Custom fan curve. The running service reads `settings.fanCurve` each tick,
     *  so the change takes effect within a tick; [onSaved] restarts the watcher to engage it immediately. */
    fun setFanCurve(curve: FanCurve, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            settingsStorage.persistFanCurve(curve)
            onSaved()
        }
    }

    /** Persist the Custom fan slew rate (%/second the fan ramps toward the curve target). */
    fun setFanResponseStep(step: Int, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            settingsStorage.persistFanResponseStep(step)
            onSaved()
        }
    }

    /** Persist the Cooler/Quieter bias (live % offset on the curve; + cooler/louder, − quieter). */
    fun setFanBias(bias: Int, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            settingsStorage.persistFanBias(bias)
            onSaved()
        }
    }

    /** Toggle the Smart (closed-loop temp-target) Custom fan controller vs the manual curve. */
    fun setFanSmartEnabled(enabled: Boolean, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            settingsStorage.persistFanSmartEnabled(enabled)
            onSaved()
        }
    }

    /** Persist the Smart-mode target SoC temperature (°C) the controller holds. */
    fun setFanTargetTemp(tempC: Int, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            settingsStorage.persistFanTargetTemp(tempC)
            onSaved()
        }
    }

    /** Live ACTUAL fan speed as a duty % from the gpio5_pwm2 duty node — the reliable "how fast is the fan"
     *  reading (the RPM tach reads 0 most of the time, even in the vendor's own mode, so we don't trust it). */
    suspend fun readFanDutyPercent(): Int? = withContext(Dispatchers.IO) {
        val duty = FanCurveController.readDutyFromDevice() ?: return@withContext null
        val period = FanCurveController.readPeriodFromDevice() ?: FanCurveController.DEFAULT_PERIOD
        if (period <= 0) null else ((duty.toLong() * 100) / period).toInt().coerceIn(0, 100)
    }

    /**
     * EVGA-style autocalibrate: sweep the fan duty low→100% (settling at each step), read the RPM tach, then
     * derive this unit's real min-spin point and a recommended curve from the samples ([FanCalibration]). The
     * sweep drives the PWM directly, so it flags [FanCurveController.externalControlActive] (the foreground
     * service stands down) for its duration and ALWAYS clears it in a `finally`. Odin-only; persists the
     * recommended curve and restarts the watcher so Custom re-engages and retakes the fan.
     */
    fun autocalibrateFan(onDone: () -> Unit = {}) {
        if (_fanCalibrating.value) return
        _fanCalibrating.value = true
        viewModelScope.launch {
            try {
                val supported = withContext(Dispatchers.IO) {
                    FanController.customFanAvailable()
                }
                if (!supported) {
                    transientError.value = "Custom fan isn't available on this device"
                    return@launch
                }
                transientMessage.value = "Calibrating fan — sweeping speeds (~15s)…"
                val cal = withContext(Dispatchers.IO) { runFanSweep() }
                settingsStorage.persistFanCurve(cal.recommendedCurve)
                transientMessage.value =
                    "Fan calibrated · idle ${cal.minSpinPercent}% · comfort ${cal.comfortPercent}% · ${cal.maxRpm} RPM max"
                transientError.value = null
                onDone() // restart the watcher so the new curve + Custom mode re-engage and retake the fan
            } finally {
                _fanCalibrating.value = false
            }
        }
    }

    /** Blocking duty sweep + tach read (run on IO). Holds [FanCurveController.externalControlActive] so the
     *  service's re-assert loop doesn't fight the writes; clears it in `finally` even on cancel/throw. */
    private suspend fun runFanSweep(): FanCalibration {
        // Read the device's idle temp first so the curve's quiet anchor sits at this unit's real resting temp.
        val idleTemp = readTelemetry().let { maxOf(it.cpuTempC ?: 0, it.gpuTempC ?: 0) }.takeIf { it > 0 }
        val period = FanCurveController.readPeriodFromDevice() ?: FanCurveController.DEFAULT_PERIOD
        val samples = mutableListOf<FanCalibrationSample>()
        FanCurveController.externalControlActive = true
        try {
            for (pct in FAN_CALIBRATION_STEPS) {
                FanCurveController.writeDutyToDevice(FanCurve.percentToDuty(pct, period))
                delay(FAN_CALIBRATION_SETTLE_MS)
                samples += FanCalibrationSample(pct, FanCurveController.readRpmFromDevice() ?: 0)
            }
        } finally {
            FanCurveController.externalControlActive = false
        }
        return FanCalibration.fromSweep(samples, idleTemp)
    }

    fun setResolutionScale(percent: Int) {
        viewModelScope.launch {
            val native = _nativeDisplay.value
                ?: withContext(Dispatchers.IO) { displayController.readNative() }?.also { _nativeDisplay.value = it }
            if (native == null) {
                transientError.value = "Couldn't read display size"
                return@launch
            }
            withContext(Dispatchers.IO) { displayController.applyScale(native, percent) }
            _resolutionScale.value = percent
            transientMessage.value = if (percent >= 100) {
                "Resolution reset to native"
            } else {
                "Render scale set to $percent%"
            }
            transientError.value = null
        }
    }

    fun applyTier(tier: PowerTier, onApplied: (String) -> Unit = {}) {
        edits.value = emptyMap()
        viewModelScope.launch {
            val outcome = globalTuning.switchTo(tier)
            reportSwitch(tier, outcome)
            val switched = outcome is TuningOutcome.Applied ||
                (outcome is TuningOutcome.Failed && outcome.reason == FailureReason.NO_SAVED_CUSTOM)
            if (switched) onApplied(tier.label)
        }
    }

    fun setPolicyValue(policy: CpuPolicyInfo, rawValue: Int) {
        val snapped = snapToSupported(policy, rawValue)
        val updatedEdits = edits.value + (policy.id to snapped)
        edits.value = updatedEdits
        transientMessage.value = null
        transientError.value = null

        val baseValues = state.value.policies.associate { cpuPolicy ->
            cpuPolicy.id to (state.value.actualValues[cpuPolicy.id] ?: cpuPolicy.currentMaxFreq)
        }
        val pendingValues = baseValues + updatedEdits
        val selectedProfile = state.value.displayProfiles
            .firstOrNull { it.id == state.value.selectedProfileId }

        if (selectedProfile != null &&
            !ProfileStateResolver.matchesProfile(pendingValues, selectedProfile, state.value.policies)
        ) {
            viewModelScope.launch {
                repository.selectProfile(null)
            }
        }
    }

    fun applyProfile(profile: PerformanceProfile) {
        edits.value = edits.value + profile.maxFrequencies
        viewModelScope.launch {
            repository.selectProfile(profile.id.takeUnless { it == ProfileStateResolver.STOCK_PROFILE_ID })
        }
    }

    fun clearSelection() {
        viewModelScope.launch {
            repository.selectProfile(null)
        }
    }

    fun consumeStatusMessage() {
        transientMessage.value = null
    }

    fun consumeErrorMessage() {
        transientError.value = null
    }

    fun applyCurrent(state: TunerState, onApplied: (String) -> Unit = {}) {
        transientMessage.value = null
        transientError.value = null

        viewModelScope.launch {
            val appliedProfile = ProfileStateResolver.preferredProfileForCurrentValues(state)
            val applyResult = globalTuning.writeCapsReassertingGpu(state.policies) {
                repository.applyValues(
                    policies = state.policies,
                    selectedValues = state.currentValues,
                    isReset = appliedProfile?.id == ProfileStateResolver.STOCK_PROFILE_ID,
                    appliedDisplayProfileId = appliedProfile?.id ?: ProfileStateResolver.MANUAL_PROFILE_ID,
                    persistAsCustom = appliedProfile == null,
                )
            }
            when (applyResult) {
                TuningOutcome.Blocked -> {
                    transientError.value = "A game owns the clocks. Limits not applied"
                    return@launch
                }
                is TuningOutcome.Failed -> {
                    transientError.value = applyResult.message ?: "Failed to apply limits"
                    return@launch
                }
                TuningOutcome.Deferred -> return@launch
                is TuningOutcome.Applied -> Unit
            }
            val outcome = applyResult.caps ?: return@launch
            edits.value = emptyMap()
            transientMessage.value = if (outcome.verificationPassed) {
                buildAppliedMessage(appliedProfile, outcome.commandOutput)
            } else {
                buildVerificationFailureMessage(state, outcome.actualValues, outcome.commandOutput)
            }
            transientError.value = null
            repository.selectProfile(appliedProfile?.id?.takeUnless { it == ProfileStateResolver.STOCK_PROFILE_ID })
            onApplied(appliedProfile?.name ?: "Manual")
        }
    }

    fun createUserProfile(name: String, state: TunerState) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            transientError.value = "Profile name is required"
            return
        }
        viewModelScope.launch {
            if (hasDuplicateProfileName(trimmedName, excludedId = null, state = state)) {
                transientError.value = "Profile name already exists"
                return@launch
            }
            repository.createUserProfile(trimmedName, state.currentValues)
            transientMessage.value = "Saved profile \"$trimmedName\""
            transientError.value = null
        }
    }

    fun updateProfile(profileId: String, name: String, state: TunerState) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            transientError.value = "Profile name is required"
            return
        }
        viewModelScope.launch {
            if (hasDuplicateProfileName(trimmedName, excludedId = profileId, state = state)) {
                transientError.value = "Profile name already exists"
                return@launch
            }
            repository.updateProfile(profileId, trimmedName, state.currentValues)
            transientMessage.value = "Updated profile \"$trimmedName\""
            transientError.value = null
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            repository.deleteProfile(profileId)
            transientMessage.value = "Deleted profile"
            transientError.value = null
        }
    }

    fun moveProfile(profileId: String, offset: Int) {
        viewModelScope.launch {
            repository.moveProfile(profileId, offset)
        }
    }

    fun resetProfilesToDefault() {
        viewModelScope.launch {
            repository.resetProfilesToDefault()
            transientMessage.value = "Restored bundled profiles and removed custom profiles"
            transientError.value = null
        }
    }

    suspend fun exportProfilesJson(): String {
        return repository.exportProfilesJson()
    }

    suspend fun importProfilesJson(rawJson: String): Int {
        val importedCount = repository.importProfilesJson(rawJson)
        transientMessage.value = if (importedCount == 1) {
            "Imported 1 profile"
        } else {
            "Imported $importedCount profiles"
        }
        transientError.value = null
        return importedCount
    }

    fun setTileTapBehavior(behavior: TileInteractionBehavior, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            settingsStorage.persistTileTapBehavior(behavior)
            onSaved()
        }
    }

    fun setAutomationEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStorage.persistAutomationEnabled(enabled) }
    }

    fun setApplyLastProfileOnBoot(enabled: Boolean) {
        viewModelScope.launch {
            settingsStorage.persistApplyLastProfileOnBoot(enabled)
        }
    }

    fun setSleepProfileEnabled(enabled: Boolean, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            settingsStorage.persistSleepProfileEnabled(enabled)
            onSaved()
        }
    }

    fun configureSleepProfile(enabled: Boolean, profileId: String?, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            settingsStorage.persistSleepProfile(enabled, profileId)
            onSaved()
        }
    }

    fun setSleepProfile(profileId: String?) {
        viewModelScope.launch {
            settingsStorage.persistSleepProfileId(profileId)
        }
    }

    fun setThemeId(themeId: com.kei.pulse.model.PulseThemeId) {
        viewModelScope.launch {
            settingsStorage.persistThemeId(themeId)
        }
    }

    fun setColorSource(colorSource: AppColorSource) {
        viewModelScope.launch {
            settingsStorage.persistColorSource(colorSource)
        }
    }

    fun setAccentColor(accentColor: Int) {
        viewModelScope.launch {
            settingsStorage.persistAccentColor(accentColor)
        }
    }

    fun setOverlayEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStorage.persistOverlayEnabled(enabled) }
    }

    fun setQuickAccess(enabled: Boolean) {
        viewModelScope.launch { settingsStorage.persistQuickAccessEnabled(enabled) }
    }

    fun setQuickAccessShowHandle(show: Boolean) {
        viewModelScope.launch { settingsStorage.persistQuickAccessShowHandle(show) }
    }

    fun clearQuickAccessCombo() {
        viewModelScope.launch { settingsStorage.persistQuickAccessCombo(null) }
    }

    private val _capturingCombo = MutableStateFlow(false)
    /** True while "Set combo" is reading the controller — drives the Settings UI's "press now" state. */
    val capturingCombo: StateFlow<Boolean> = _capturingCombo

    /** Press-to-capture: read the next combo the user holds (via getevent) and save it. */
    fun captureQuickAccessCombo() {
        if (_capturingCombo.value) return
        _capturingCombo.value = true
        viewModelScope.launch {
            try {
                val combo = com.kei.pulse.data.InputComboWatcher().captureNext()
                if (combo.isNotEmpty()) {
                    settingsStorage.persistQuickAccessCombo(com.kei.pulse.data.InputComboParser.encode(combo))
                }
            } finally {
                // Always clear, even on a throw/cancellation — a stuck flag wedges the Settings UI with no recovery.
                _capturingCombo.value = false
            }
        }
    }

    fun setOverlayPreset(preset: com.kei.pulse.model.OverlayPreset) {
        // Settings chip = quick-fill: set the layout AND seed the shown-items set from the preset's bundle.
        // (The in-overlay LAYOUT cycle persists only the preset, so it never wipes a custom item set.)
        viewModelScope.launch {
            settingsStorage.persistOverlayPreset(preset)
            settingsStorage.persistOverlayElements(preset.elements)
        }
    }

    fun setOverlayElement(element: com.kei.pulse.model.OverlayElement, on: Boolean) {
        viewModelScope.launch {
            val current = settingsStorage.settings.first().overlayElements
            settingsStorage.persistOverlayElements(if (on) current + element else current - element)
        }
    }

    fun setOverlayOpacity(opacity: Int) {
        viewModelScope.launch { settingsStorage.persistOverlayOpacity(opacity) }
    }

    fun refreshLiveState() {
        repository.refreshLiveValues()
    }

    private fun snapToSupported(policy: CpuPolicyInfo, rawValue: Int): Int {
        return policy.supportedFrequencies.minByOrNull { supported -> abs(supported - rawValue) }
            ?: rawValue
    }

    private fun hasDuplicateProfileName(
        name: String,
        excludedId: String?,
        state: TunerState,
    ): Boolean {
        return state.displayProfiles
            .filter { profile -> profile.source != ProfileSource.VIRTUAL }
            .any { profile ->
                profile.id != excludedId && profile.name.equals(name, ignoreCase = true)
            }
    }

    private fun buildAppliedMessage(
        appliedProfile: PerformanceProfile?,
        commandOutput: String?,
    ): String {
        val base = if (appliedProfile != null) {
            "Applied profile: ${appliedProfile.name}"
        } else {
            "Applied profile: Manual"
        }
        return commandOutput?.takeIf { it.isNotBlank() }?.let { "$base | log: ${it.take(120)}" } ?: base
    }

    private fun buildVerificationFailureMessage(
        state: TunerState,
        actualValues: Map<Int, Int>,
        commandOutput: String?,
    ): String {
        val summary = state.policies.joinToString(", ") { policy ->
            val requested = state.currentValues[policy.id] ?: policy.currentMaxFreq
            val actual = actualValues[policy.id] ?: policy.currentMaxFreq
            "C${policy.id} requested ${formatFrequency(requested)}, " +
                "actual ${formatFrequency(actual, boosted = actual > policy.selectableMaxFreq)}"
        }
        val base = "Apply did not stick: $summary"
        return commandOutput?.takeIf { it.isNotBlank() }?.let { "$base | log: ${it.take(120)}" } ?: base
    }

    companion object {
        /** Duty %s the autocalibrate sweep steps through, low→100% (the fan's real start point is found here). */
        private val FAN_CALIBRATION_STEPS = listOf(20, 30, 40, 50, 60, 70, 80, 90, 100)
        /** Settle time per step so the fan reaches a stable RPM before the tach is sampled. */
        private const val FAN_CALIBRATION_SETTLE_MS = 1_500L

        fun factory(
            repository: PerformanceRepository,
            settingsStorage: SettingsStorage,
            telemetryReader: TelemetryReader,
            globalTuning: GlobalTuning,
            perAppConfigStorage: PerAppConfigStorage? = null,
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return TunerViewModel(repository, settingsStorage, telemetryReader, globalTuning, perAppConfigStorage) as T
                }
            }
        }
    }
}

data class TuningReadback(
    val expectedCaps: Map<Int, Int> = emptyMap(),
    val gpuMinLevel: Int? = null,
    val gpuMaxLevel: Int? = null,
    val owner: ClockOwner = ClockOwner.Free,
)
