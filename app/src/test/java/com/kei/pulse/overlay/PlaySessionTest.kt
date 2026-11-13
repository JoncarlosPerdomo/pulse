package com.kei.pulse.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaySessionTest {

    @Test
    fun pausesAwayAndResumesOnReturn() {
        val session = PlaySession().resume("game", 0).pause(1_000).resume("game", 5_000)
        assertEquals(1_500, session.elapsedMs(5_500))
    }

    @Test
    fun aDifferentGameStartsFromZero() {
        val session = PlaySession().resume("a", 0).pause(1_000).resume("b", 2_000)
        assertEquals(500, session.elapsedMs(2_500))
    }

    @Test
    fun rebindingTheRunningGameKeepsItsTime() {
        val session = PlaySession().resume("game", 0).resume("game", 3_000)
        assertEquals(4_000, session.elapsedMs(4_000))
    }
}
