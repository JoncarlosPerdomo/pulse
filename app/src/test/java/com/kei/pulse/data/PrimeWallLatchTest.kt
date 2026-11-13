package com.kei.pulse.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimeWallLatchTest {

    private fun PrimeWallLatch.feed(vararg ticks: Pair<Boolean, Boolean>) =
        ticks.fold(this) { latch, (walled, reached) -> latch.next(walled, reached) }

    private val walled = true to false
    private val reached = false to true
    private val nearMiss = false to false

    @Test
    fun engagesAfterThreeWalledTicksAndNearMissesCantReleaseIt() {
        assertFalse(PrimeWallLatch().feed(walled, walled).latched)
        val latched = PrimeWallLatch().feed(walled, walled, walled)
        assertTrue(latched.latched)
        assertTrue(latched.feed(nearMiss, nearMiss, nearMiss, nearMiss).latched)
    }

    @Test
    fun releasesOnlyAfterThreeTicksOnTarget() {
        val latched = PrimeWallLatch().feed(walled, walled, walled)
        assertTrue(latched.feed(reached, reached).latched)
        assertFalse(latched.feed(reached, reached, reached).latched)
        assertTrue("a walled tick resets the release count", latched.feed(reached, reached, walled, reached).latched)
    }
}
