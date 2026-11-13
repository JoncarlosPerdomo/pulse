package com.kei.pulse.appwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundDebounceTest {

    private fun feed(vararg polls: String?): ForegroundState =
        polls.fold(ForegroundState(confirmed = "home")) { state, fg -> ForegroundDebounce.next(state, fg) }

    @Test
    fun confirmsAfterTwoConsecutivePolls() {
        assertEquals("home", feed("game").confirmed)
        assertEquals("game", feed("game", "game").confirmed)
    }

    @Test
    fun aFlapBackToTheConfirmedAppDropsTheCandidate() {
        val state = feed("splash", "home", "splash")
        assertEquals("home", state.confirmed)
        assertEquals(ForegroundState.Pending("splash", 1), state.pending)
    }

    @Test
    fun aMissingSampleKeepsTheCandidate() {
        assertEquals("game", feed("game", null, "game").confirmed)
        assertNull(feed("game", "game").pending)
    }
}
