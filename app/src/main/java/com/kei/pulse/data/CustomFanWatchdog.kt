package com.kei.pulse.data

import com.kei.pulse.root.SysfsBatch
import java.io.File
import java.io.IOException

/**
 * Hands the fan back to vendor Smart if Pulse dies while driving the Custom fan. Pulse rewrites a heartbeat
 * file every poll tick; a detached root script restores Smart once the heartbeat stops changing.
 */
class CustomFanWatchdog(
    private val heartbeat: File,
    private val now: () -> Long = { System.nanoTime() / NANOS_PER_MS },
    private val launch: (String) -> String?,
) {
    sealed interface State {
        data object Idle : State
        data class Armed(val lastBeatMs: Long) : State
        data class BackingOff(val retryAtMs: Long) : State
    }

    enum class Step { Wait, Relaunch, Beat }

    private var state: State = State.Idle

    /**
     * Starts the watcher when it is not known to be running and records a heartbeat. `true` means the fan may
     * be driven; `false` means do not drive it (the watcher could not be armed, retried after [BACKOFF_MS]).
     */
    @Synchronized
    fun startOrBeatBeforeDriving(): Boolean {
        val step = step(state, now())
        if (step == Step.Wait) return false
        val armed = try {
            writeHeartbeat(now())
            step == Step.Beat || launch(script(heartbeat.absolutePath))?.trim() == STARTED
        } catch (_: IOException) {
            false
        }
        state = if (armed) State.Armed(now()) else State.BackingOff(now() + BACKOFF_MS)
        return armed
    }

    @Synchronized
    fun stop() {
        if (state is State.Armed) state = State.Idle
    }

    private fun writeHeartbeat(timeMs: Long) {
        val next = File(heartbeat.parentFile, "${heartbeat.name}.next")
        next.writeText(timeMs.toString())
        if (!next.renameTo(heartbeat)) throw IOException("Cannot replace $heartbeat")
    }

    companion object {
        private const val NANOS_PER_MS = 1_000_000L
        private const val CHECK_INTERVAL_SECONDS = 1
        private const val STALE_CHECKS = 5

        /** How often the service ticks, and so how often it beats. */
        const val POLL_INTERVAL_MS = 1_000L

        /** The script restores Smart once the heartbeat has not changed for this long. */
        const val STALE_AFTER_MS = STALE_CHECKS * CHECK_INTERVAL_SECONDS * 1_000L

        /** A gap this long between beats leaves less than one tick before the script restores Smart. */
        const val RELAUNCH_AFTER_MS = STALE_AFTER_MS - POLL_INTERVAL_MS
        const val BACKOFF_MS = STALE_AFTER_MS

        const val STARTED = "STARTED"
        const val LOCK_PATH = "/data/local/tmp/pulse-fan-watchdog.lock"

        fun step(state: State, nowMs: Long): Step = when (state) {
            State.Idle -> Step.Relaunch
            is State.Armed -> if (nowMs - state.lastBeatMs >= RELAUNCH_AFTER_MS) Step.Relaunch else Step.Beat
            is State.BackingOff -> if (nowMs >= state.retryAtMs) Step.Relaunch else Step.Wait
        }

        fun script(heartbeatPath: String, lockPath: String = LOCK_PATH): String {
            val heartbeat = SysfsBatch.shellQuote(heartbeatPath)
            return """
                heartbeat=$heartbeat
                lock=${SysfsBatch.shellQuote(lockPath)}
                if [ "${'$'}1" != watch ]; then
                    setsid sh "${'$'}0" watch </dev/null >/dev/null 2>&1 &
                    echo $STARTED
                    exit
                fi
                missing_pid_checks=0
                until mkdir "${'$'}lock" 2>/dev/null; do
                    owner_pid=${'$'}(cat "${'$'}lock/pid" 2>/dev/null)
                    if [ -z "${'$'}owner_pid" ]; then
                        missing_pid_checks=${'$'}((missing_pid_checks + 1))
                        if [ "${'$'}missing_pid_checks" -gt 1 ]; then rmdir "${'$'}lock" 2>/dev/null; continue; fi
                    elif tr '\000' ' ' < /proc/"${'$'}owner_pid"/cmdline 2>/dev/null | grep -F "${'$'}0 watch" >/dev/null; then
                        kill "${'$'}owner_pid"
                    else
                        rm -f "${'$'}lock/pid"
                        rmdir "${'$'}lock" 2>/dev/null
                        continue
                    fi
                    sleep $CHECK_INTERVAL_SECONDS
                done
                echo ${'$'}${'$'} > "${'$'}lock/pid"
                trap 'rm -f "${'$'}lock/pid"; rmdir "${'$'}lock"' EXIT
                last_beat=
                stale_checks=0
                while :; do
                    beat=${'$'}(cat "${'$'}heartbeat" 2>/dev/null)
                    if [ -z "${'$'}beat" ] || [ "${'$'}beat" = "${'$'}last_beat" ]; then
                        stale_checks=${'$'}((stale_checks + 1))
                    else
                        stale_checks=0
                    fi
                    [ "${'$'}stale_checks" -ge $STALE_CHECKS ] && break
                    last_beat=${'$'}beat
                    sleep $CHECK_INTERVAL_SECONDS
                done
                if [ "${'$'}(settings get system fan_mode)" = ${FanController.CUSTOM} ]; then
                    ${FanController.vendorModeCommand(FanController.SMART)}
                fi
            """.trimIndent()
        }
    }
}
