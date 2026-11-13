package com.kei.pulse.appwatch

import com.kei.pulse.model.PerAppConfig
import com.kei.pulse.model.PowerTier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundCapReassertionTest {
    @Test
    fun `Stock stays hands off while Max and explicit floor extras reassert`() {
        val stock = PerAppConfig("game", profileBinding = PerAppConfig.AUTO_OFF_BINDING)
        val max = stock.copy(profileBinding = PerAppConfig.tierBinding(PowerTier.MAX))
        assertFalse(shouldReassertBoundCaps(stock, null))
        assertFalse(shouldReassertBoundCaps(null, null))
        assertTrue(shouldReassertBoundCaps(max, null))
        assertTrue(shouldReassertBoundCaps(stock.copy(gpuFloorPercent = 50), null))
        assertFalse(shouldReassertBoundCaps(max, "game"))
    }
}
