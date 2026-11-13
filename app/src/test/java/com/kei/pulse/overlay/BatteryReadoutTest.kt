package com.kei.pulse.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryReadoutTest {

    @Test
    fun smoothsWithinARegimeAndReseedsAcrossAPlug() {
        val onBattery = BatteryReadout()
            .next(discharging = true, sampleW = 10f, capacityWh = 20f, batteryPercent = 50)
            .next(discharging = true, sampleW = 20f, capacityWh = 20f, batteryPercent = 50)
        assertEquals(13f, onBattery.powerW!!, 0.001f)

        val plugged = onBattery.next(discharging = false, sampleW = 5f, capacityWh = 20f, batteryPercent = 50)
        assertEquals("charge rate starts fresh, not blended with draw", 5f, plugged.powerW!!, 0.001f)
        assertEquals("time to full re-seeded: 10 Wh at 5 W", 120, plugged.minutes)
    }

    @Test
    fun aMissingSampleHoldsThePreviousValue() {
        val readout = BatteryReadout()
            .next(discharging = true, sampleW = 8f, capacityWh = null, batteryPercent = null)
            .next(discharging = true, sampleW = null, capacityWh = null, batteryPercent = null)
        assertEquals(8f, readout.powerW!!, 0.001f)
        assertNull(readout.minutes)
    }
}
