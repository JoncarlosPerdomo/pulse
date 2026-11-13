package com.kei.pulse.appwatch

/** The debounced foreground app: [confirmed], plus a [pending] change still being confirmed. */
data class ForegroundState(val confirmed: String? = null, val pending: Pending? = null) {
    data class Pending(val pkg: String, val polls: Int)
}

/**
 * A game's foreground package can flap with overlays/splash activities, and acting on a single sample causes
 * apply→restore thrash. A new package is [ForegroundState.confirmed] only after [CONFIRM_POLLS] consecutive polls.
 */
object ForegroundDebounce {
    const val CONFIRM_POLLS = 2

    fun next(prev: ForegroundState, foreground: String?): ForegroundState = when {
        foreground != null && foreground != prev.confirmed -> {
            val polls = if (foreground == prev.pending?.pkg) prev.pending.polls + 1 else 1
            if (polls >= CONFIRM_POLLS) {
                ForegroundState(confirmed = foreground)
            } else {
                prev.copy(pending = ForegroundState.Pending(foreground, polls))
            }
        }
        foreground == prev.confirmed -> prev.copy(pending = null)
        else -> prev
    }
}
