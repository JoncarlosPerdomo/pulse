package com.kei.pulse.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Golden replay fixture: a real Stray session on the **Retroid Pocket 6** (SD 8 Gen 2 / QCS8550) at AutoTDP
 * 60 with **SMOOTH** bias. This is the only SMOOTH fixture (the others are EFFICIENT) and the only heavy game
 * on the non-Odin path — SMOOTH favors frames over efficiency (wider jitter gate, 87 °C ceiling) and there is
 * no power ceiling, so the controller chases clocks hard (near the opposite of Stray-on-Odin-EFFICIENT).
 *
 * Fixture: `app/src/test/resources/autotdp/stray_rp6_smooth60.logcat`.
 */
class StrayRp6SmoothFixtureTest {

    private fun load(): String =
        javaClass.getResourceAsStream("/autotdp/stray_rp6_smooth60.logcat")!!.bufferedReader().readText()

    @Test
    fun replayedTrajectoryIsStable() {
        val result = AutoTdpReplay.replay(load())
        // GOLDEN trajectory: seeded from the recorded opening (trimmed) caps, SMOOTH on a below-target heavy
        // game CHASES — it raises every tick toward the unreachable 60 and never harvests. (Before warm-start
        // seeding this replayed degenerately as all-HOLD; seeding now reproduces the recorded RAISE-heavy chase.)
        val expected = listOf("HOLD") + List(20) { "RAISE" }
        assertEquals(expected, result.replayedActions)
        // The meaningful SMOOTH invariant: it never HARVESTS a below-target heavy game (would cost frames) —
        // a regression that made SMOOTH trim here would introduce TRIM and fail this.
        assertFalse("SMOOTH never harvests a below-target heavy game", result.replayedActions.contains("TRIM"))
    }
}
