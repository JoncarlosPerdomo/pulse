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
    internal sealed interface State {
        data object Idle : State
        data class Armed(val lastBeatMs: Long) : State
        data class BackingOff(val retryAtMs: Long) : State
    }

    internal enum class Action { Beat, Launch, Withhold }

    internal enum class Arming { Succeeded, Failed }

    private var state: State = State.Idle

    /**
     * Starts the watcher when it is not known to be running and records a heartbeat. `true` means the fan may
     * be driven; `false` means do not drive it (the watcher could not be armed, retried after [BACKOFF_MS]).
     */
    @Synchronized
    fun startOrBeatBeforeDriving(): Boolean {
        val nowMs = now()
        val action = actionFor(state, nowMs)
        if (action == Action.Withhold) return false
        val arming = try {
            writeHeartbeat(nowMs)
            if (action == Action.Beat || launch(script(heartbeat.absolutePath))?.trim() == STARTED) {
                Arming.Succeeded
            } else {
                Arming.Failed
            }
        } catch (_: IOException) {
            Arming.Failed
        }
        state = next(state, nowMs, arming)
        return arming == Arming.Succeeded
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
        private const val MS_PER_SECOND = 1_000L
        private const val MS_PER_UPTIME_CENTISECOND = 10L
        private const val CHECK_INTERVAL_SECONDS = 1L

        /**
         * The script restores Smart once the heartbeat has not changed for this long, measured on its own
         * clock. A beat this late means the script may already have given up, so Pulse relaunches it; any
         * sooner and a slow tick would displace a watcher that is still waiting.
         */
        internal const val STALE_AFTER_MS = 5 * MS_PER_SECOND
        internal const val BACKOFF_MS = STALE_AFTER_MS

        internal const val STARTED = "STARTED"
        internal const val LOCK_PATH = "/data/local/tmp/pulse-fan-watchdog.lock"

        internal fun actionFor(state: State, nowMs: Long): Action = when (state) {
            State.Idle -> Action.Launch
            is State.Armed -> if (nowMs - state.lastBeatMs >= STALE_AFTER_MS) Action.Launch else Action.Beat
            is State.BackingOff -> if (nowMs >= state.retryAtMs) Action.Launch else Action.Withhold
        }

        internal fun next(state: State, nowMs: Long, arming: Arming?): State = when (arming) {
            Arming.Succeeded -> State.Armed(nowMs)
            Arming.Failed -> State.BackingOff(nowMs + BACKOFF_MS)
            null -> state
        }

        internal fun script(
            heartbeatPath: String,
            lockPath: String = LOCK_PATH,
            staleAfterMs: Long = STALE_AFTER_MS,
        ): String {
            val heartbeat = SysfsBatch.shellQuote(heartbeatPath)
            val bounceMode = FanController.bounceModeFor(FanController.SMART)
            return """
                heartbeat=$heartbeat
                lock=${SysfsBatch.shellQuote(lockPath)}
                restoring=${SysfsBatch.shellQuote("$lockPath.restoring")}
                if [ "${'$'}1" != watch ]; then
                    setsid sh "${'$'}0" watch </dev/null >/dev/null 2>&1 &
                    echo $STARTED
                    exit
                fi
                restore_smart() {
                    trap '' TERM
                    : > "${'$'}restoring"
                    ${FanController.vendorModeCommand(FanController.SMART)}
                    rm -f "${'$'}restoring"
                    trap - TERM
                }
                read_clock() {
                    read uptime _ < /proc/uptime
                    now_ms=${'$'}(( ${'$'}{uptime%.*}${'$'}{uptime#*.} * $MS_PER_UPTIME_CENTISECOND ))
                }
                empty_lock=none
                until mkdir "${'$'}lock" 2>/dev/null; do
                    owner_pid=${'$'}(cat "${'$'}lock/pid" 2>/dev/null)
                    if [ -z "${'$'}owner_pid" ]; then
                        lock_id=${'$'}(stat -c %y "${'$'}lock" 2>/dev/null)
                        if [ "${'$'}lock_id" = "${'$'}empty_lock" ]; then rmdir "${'$'}lock" 2>/dev/null; continue; fi
                        empty_lock=${'$'}lock_id
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
                if [ -e "${'$'}restoring" ] && [ "${'$'}(settings get system fan_mode)" = $bounceMode ]; then restore_smart; fi
                rm -f "${'$'}restoring"
                last_beat=
                read_clock
                last_change_ms=${'$'}now_ms
                while :; do
                    beat=${'$'}(cat "${'$'}heartbeat" 2>/dev/null)
                    read_clock
                    if [ -n "${'$'}beat" ] && [ "${'$'}beat" != "${'$'}last_beat" ]; then
                        last_beat=${'$'}beat
                        last_change_ms=${'$'}now_ms
                    fi
                    [ ${'$'}((now_ms - last_change_ms)) -ge $staleAfterMs ] && break
                    sleep $CHECK_INTERVAL_SECONDS
                done
                if [ "${'$'}(settings get system fan_mode)" = ${FanController.CUSTOM} ]; then restore_smart; fi
            """.trimIndent()
        }
    }
}
