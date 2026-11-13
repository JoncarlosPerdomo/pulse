package com.kei.pulse.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden replay fixture: a real ~2-minute Stray session on the Odin 3 (AutoTDP 60, EFFICIENT) captured from
 * `PulseAutoTdp` and re-run through the actual [AutoTuneController]. This is the "20-minute physical test →
 * millisecond JVM test" deliverable — a controller change that alters decisions on this real prime-walled
 * session changes the trajectory and fails CI, surfacing the behavior change for review.
 *
 * The fixture lives at `app/src/test/resources/autotdp/stray_odin3_efficient60.logcat`.
 */
class StrayOdin3FixtureTest {

    private fun load(): String =
        javaClass.getResourceAsStream("/autotdp/stray_odin3_efficient60.logcat")!!.bufferedReader().readText()

    @Test
    fun replayedTrajectoryIsStable() {
        val result = AutoTdpReplay.replay(load())
        // GOLDEN trajectory: all HOLD. The replay is seeded from the recorded opening caps — this session was
        // already converged (the device held steady at 0:35,6:100,-100:40 the whole time), so the controller
        // starts there and HOLDS, matching what the device actually did. A controller change that alters these
        // decisions changes this list ⇒ the test fails for review.
        val expected = List(26) { "HOLD" }
        assertEquals(expected, result.replayedActions)
        // Invariant: the vendor-floored prime (policy 6) is never trimmed — the power ceiling and the
        // prime-walled settle both skip it (capping it sheds no watts/heat).
        assertEquals("prime cluster left untrimmed", 100, result.outcomes.last().replayedCaps[6])
    }
}
