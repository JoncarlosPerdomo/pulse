package com.kei.pulse.overlay

/**
 * The overlay's play timer for one game: running while it's in front ([resumedAtMs] set), paused while it's
 * away. Returning to the same game resumes the count; a different game starts from zero.
 */
data class PlaySession(
    val pkg: String? = null,
    val accumulatedMs: Long = 0L,
    val resumedAtMs: Long? = null,
) {
    fun resume(pkg: String, nowMs: Long): PlaySession = when {
        pkg != this.pkg -> PlaySession(pkg = pkg, resumedAtMs = nowMs)
        resumedAtMs != null -> this
        else -> copy(resumedAtMs = nowMs)
    }

    fun pause(nowMs: Long): PlaySession =
        resumedAtMs?.let { copy(accumulatedMs = accumulatedMs + (nowMs - it), resumedAtMs = null) } ?: this

    fun elapsedMs(nowMs: Long): Long = accumulatedMs + (resumedAtMs?.let { nowMs - it } ?: 0L)
}
