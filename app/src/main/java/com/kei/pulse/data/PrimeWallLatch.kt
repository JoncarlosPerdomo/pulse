package com.kei.pulse.data

/**
 * AutoTDP's "the fps target is unreachable because the prime is walled" hysteresis latch: engages after
 * [CONFIRM] walled ticks and releases only after [RELEASE] ticks of reaching the full target, so a lone
 * near-miss or lucky spike can't flip it. Ticks that are neither walled nor on target keep both streaks.
 */
internal data class PrimeWallLatch(
    val confirmStreak: Int = 0,
    val releaseStreak: Int = 0,
    val latched: Boolean = false,
) {
    fun next(walled: Boolean, reachedTarget: Boolean): PrimeWallLatch = when {
        walled -> {
            val confirm = (confirmStreak + 1).coerceAtMost(CONFIRM)
            PrimeWallLatch(confirmStreak = confirm, releaseStreak = 0, latched = latched || confirm >= CONFIRM)
        }
        reachedTarget -> {
            val release = releaseStreak + 1
            if (release >= RELEASE) PrimeWallLatch(releaseStreak = release) else copy(releaseStreak = release)
        }
        else -> this
    }

    companion object {
        const val CONFIRM = 3
        const val RELEASE = 3
    }
}
