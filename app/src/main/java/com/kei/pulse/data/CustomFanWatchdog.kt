package com.kei.pulse.data

import com.kei.pulse.root.SysfsBatch
import java.io.File
import java.io.IOException

/**
 * Hands the fan back to vendor Smart if Pulse dies while driving the Custom fan. Pulse rewrites a heartbeat
 * file every poll tick; a detached root script restores Smart once the heartbeat stops changing.
 *
 * [now] must run on the boot-time clock the script reads from `/proc/uptime`.
 */
class CustomFanWatchdog(
    private val heartbeat: File,
    private val now: () -> Long,
    private val launch: (String) -> String?,
) {
    enum class Status { Armed, BackoffStarted, BackingOff }

    internal sealed interface State {
        data object Idle : State
        data class Armed(val lastBeatMs: Long) : State
        data class BackingOff(val retryAtMs: Long) : State
    }

    internal enum class Action { Beat, Launch, Withhold }

    internal enum class LaunchOutcome { Succeeded, Failed }

    private var state: State = State.Idle

    /**
     * Starts the watcher when it is not known to be running and records a heartbeat. [Status.Armed] means the
     * fan may be driven. Otherwise do not drive it: the watcher could not be armed and is retried after
     * [STALE_AFTER_MS]; [Status.BackoffStarted] marks the one call that entered that back-off.
     */
    @Synchronized
    fun startOrBeatBeforeDriving(): Status {
        val startedAt = now()
        if (actionFor(state, startedAt) == Action.Withhold) return Status.BackingOff
        var stampedAt = startedAt
        val outcome = try {
            writeHeartbeat(startedAt)
            stampedAt = now()
            if (actionFor(state, stampedAt) == Action.Beat || launch(script(heartbeat.absolutePath))?.trim() == STARTED) {
                LaunchOutcome.Succeeded
            } else {
                LaunchOutcome.Failed
            }
        } catch (_: IOException) {
            LaunchOutcome.Failed
        }
        state = next(state, stampedAt, outcome)
        return if (outcome == LaunchOutcome.Succeeded) Status.Armed else Status.BackoffStarted
    }

    @Synchronized
    fun stop() {
        if (state is State.Armed) state = State.Idle
    }

    private fun writeHeartbeat(timeMs: Long) {
        val staged = File(heartbeat.parentFile, "${heartbeat.name}.next")
        staged.writeText(timeMs.toString())
        if (!staged.renameTo(heartbeat)) throw IOException("Cannot replace $heartbeat")
    }

    companion object {
        private const val MS_PER_SECOND = 1_000L
        private const val MS_PER_UPTIME_CENTISECOND = 10L
        private const val CHECK_INTERVAL_SECONDS = 1L
        private const val LOCK_POLL_SECONDS = "0.1"
        private const val LOCK_POLLS = 30

        internal const val STALE_AFTER_MS = 5 * MS_PER_SECOND
        internal const val STARTED = "STARTED"
        internal const val LOCK_PATH = "/data/local/tmp/pulse-fan-watchdog.lock"

        internal fun actionFor(state: State, nowMs: Long): Action = when (state) {
            State.Idle -> Action.Launch
            is State.Armed -> if (nowMs - state.lastBeatMs >= STALE_AFTER_MS) Action.Launch else Action.Beat
            is State.BackingOff -> if (nowMs >= state.retryAtMs) Action.Launch else Action.Withhold
        }

        internal fun next(state: State, nowMs: Long, outcome: LaunchOutcome): State = when (outcome) {
            LaunchOutcome.Succeeded -> State.Armed(nowMs)
            LaunchOutcome.Failed -> State.BackingOff(nowMs + STALE_AFTER_MS)
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
                ack=${SysfsBatch.shellQuote("$lockPath.ack")}
                if [ "${'$'}1" != watch ]; then
                    rm -f "${'$'}ack"
                    setsid sh "${'$'}0" watch </dev/null >/dev/null 2>&1 &
                    polls=0
                    until [ -e "${'$'}ack" ] || [ "${'$'}polls" -ge $LOCK_POLLS ]; do
                        sleep $LOCK_POLL_SECONDS
                        polls=${'$'}((polls + 1))
                    done
                    [ -e "${'$'}ack" ] || exit 1
                    rm -f "${'$'}ack"
                    echo $STARTED
                    exit
                fi
                read_clock() {
                    read uptime _ < /proc/uptime
                    fraction=${'$'}{uptime#*.}
                    fraction=${'$'}{fraction#0}
                    now_ms=${'$'}(( ${'$'}{uptime%.*} * $MS_PER_SECOND + ${'$'}{fraction:-0} * $MS_PER_UPTIME_CENTISECOND ))
                }
                restore_smart() {
                    trap '' TERM
                    read_clock
                    echo ${'$'}now_ms > "${'$'}restoring"
                    ${FanController.vendorModeCommand(FanController.SMART)}
                    rm -f "${'$'}restoring"
                    trap - TERM
                }
                empty_lock=none
                lock_polls=0
                until mkdir "${'$'}lock" 2>/dev/null; do
                    owner_pid=${'$'}(cat "${'$'}lock/pid" 2>/dev/null)
                    if [ -z "${'$'}owner_pid" ]; then
                        lock_id=${'$'}(stat -c %y "${'$'}lock" 2>/dev/null)
                        if [ "${'$'}lock_id" = "${'$'}empty_lock" ]; then rmdir "${'$'}lock" 2>/dev/null; else empty_lock=${'$'}lock_id; fi
                    elif tr '\000' ' ' < /proc/"${'$'}owner_pid"/cmdline 2>/dev/null | grep -F "${'$'}0 watch" >/dev/null; then
                        kill "${'$'}owner_pid"
                    else
                        rm -f "${'$'}lock/pid"
                        rmdir "${'$'}lock" 2>/dev/null
                    fi
                    lock_polls=${'$'}((lock_polls + 1))
                    [ "${'$'}lock_polls" -ge $LOCK_POLLS ] && exit 1
                    sleep $LOCK_POLL_SECONDS
                done
                echo ${'$'}${'$'} > "${'$'}lock/pid"
                trap 'rm -f "${'$'}lock/pid"; rmdir "${'$'}lock"' EXIT
                if [ -e "${'$'}restoring" ]; then
                    read marker_ms < "${'$'}restoring"
                    read_clock
                    marker_age=${'$'}((now_ms - ${'$'}{marker_ms:-0}))
                    if [ "${'$'}marker_age" -ge 0 ] && [ "${'$'}marker_age" -le $staleAfterMs ] && [ "${'$'}(settings get system fan_mode)" = $bounceMode ]; then restore_smart; fi
                    rm -f "${'$'}restoring"
                fi
                : > "${'$'}ack"
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
                    if [ ${'$'}((now_ms - last_change_ms)) -ge $staleAfterMs ]; then
                        [ "${'$'}(settings get system fan_mode)" = ${FanController.CUSTOM} ] || break
                        beat=${'$'}(cat "${'$'}heartbeat" 2>/dev/null)
                        if [ -z "${'$'}beat" ] || [ "${'$'}beat" = "${'$'}last_beat" ]; then restore_smart; break; fi
                    fi
                    sleep $CHECK_INTERVAL_SECONDS
                done
            """.trimIndent()
        }
    }
}
