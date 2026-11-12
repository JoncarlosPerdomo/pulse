package com.kei.pulse.model

sealed interface ClockOwner {
    data object Free : ClockOwner

    /** A foreground game with its own per-app binding; [autoTdp] when that binding is an AutoTDP session. */
    data class BoundGame(val autoTdp: Boolean) : ClockOwner

    /** An AutoTDP session running from the global default (no per-app binding of its own). */
    data object GlobalAutoTdp : ClockOwner
}

/** What global tuning may do while it holds clock ownership steady (see [ClockOwnership.withOwnership]). */
interface ClockSession {
    val owner: ClockOwner

    /** Stop a [ClockOwner.GlobalAutoTdp] session so a global write doesn't fight it. */
    suspend fun releaseGlobalAutoTdp()

    /** Global tuning just changed the device caps; a bound game's cap hold must pick up the new ones. */
    suspend fun capsWritten()
}

/** Who may write the CPU/GPU caps right now. Global tuning defers to it before touching the device. */
interface ClockOwnership {
    /** Runs [block] while no game can bind or unbind, so the owner it sees holds for every write it makes. */
    suspend fun <T> withOwnership(block: suspend ClockSession.() -> T): T

    companion object {
        val FREE: ClockOwnership = object : ClockOwnership {
            private val session = object : ClockSession {
                override val owner: ClockOwner = ClockOwner.Free
                override suspend fun releaseGlobalAutoTdp() = Unit
                override suspend fun capsWritten() = Unit
            }

            override suspend fun <T> withOwnership(block: suspend ClockSession.() -> T): T = session.block()
        }
    }
}
