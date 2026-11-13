package com.kei.pulse.overlay

/**
 * The HUD's smoothed battery readout. Discharging, [powerW] is system draw and the minutes are time left;
 * charging, [powerW] is the charge rate into the battery and the minutes are time to full. The regimes have
 * different magnitudes, so a plug/unplug re-seeds both EMAs instead of blending across the switch.
 */
data class BatteryReadout(
    val discharging: Boolean? = null,
    val powerW: Float? = null,
    val minutesEma: Float? = null,
) {
    val minutes: Int? get() = displayMinutes(minutesEma)

    fun next(discharging: Boolean, sampleW: Float?, capacityWh: Float?, batteryPercent: Int?): BatteryReadout {
        val sameRegime = discharging == this.discharging
        val prevPower = if (sameRegime) powerW else null
        val power = sampleW?.let { w -> prevPower?.let { it * (1f - POWER_ALPHA) + w * POWER_ALPHA } ?: w } ?: prevPower
        val rawMinutes = if (discharging) {
            batteryMinutesLeft(capacityWh, batteryPercent, power, discharging = true)
        } else {
            batteryMinutesToFull(capacityWh, batteryPercent, chargeWatts = power)
        }
        val ema = smoothMinutes(if (sameRegime) minutesEma else null, rawMinutes, MINUTES_SMOOTH_ALPHA)
        return BatteryReadout(discharging, power, ema)
    }

    private companion object {
        const val POWER_ALPHA = 0.3f
    }
}
