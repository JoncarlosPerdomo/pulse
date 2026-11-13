package com.kei.pulse.data

import java.io.File

class CustomFanWatchdog(
    private val heartbeat: File,
    private val now: () -> Long = System::nanoTime,
    private val launch: (String) -> Boolean,
) {
    private var lastRefresh: Long? = null

    @Synchronized
    fun refresh(): Boolean {
        val time = now()
        val previous = lastRefresh
        if (previous == null || time - previous >= 4_000_000_000L) {
            heartbeat.writeText("stopped")
            if (!launch(script(heartbeat.absolutePath))) return false
        }
        val next = File(heartbeat.parentFile, "${heartbeat.name}.next")
        next.writeText(time.toString())
        check(next.renameTo(heartbeat))
        lastRefresh = time
        return true
    }

    @Synchronized
    fun stop() {
        lastRefresh = null
    }

    companion object {
        fun script(heartbeatPath: String): String {
            val path = "'${heartbeatPath.replace("'", "'\"'\"'")}'"
            return """
                H=$path
                L=/data/local/tmp/pulse-fan-watchdog.lock
                if [ "${'$'}1" != watch ]; then
                    tries=0
                    until mkdir "${'$'}L" 2>/dev/null; do
                        pid=${'$'}(cat "${'$'}L/pid" 2>/dev/null)
                        if [ -n "${'$'}pid" ] && ! kill -0 "${'$'}pid" 2>/dev/null; then
                            rm -f "${'$'}L/pid"
                            rmdir "${'$'}L" 2>/dev/null
                        fi
                        tries=${'$'}((tries + 1))
                        [ "${'$'}tries" -ge 4 ] && exit 1
                        sleep 1
                    done
                    echo 0 > "${'$'}H"
                    setsid sh "${'$'}0" watch </dev/null >/dev/null 2>&1 &
                    echo ${'$'}! > "${'$'}L/pid"
                    echo STARTED
                    exit
                fi
                trap 'rm -f "${'$'}L/pid"; rmdir "${'$'}L"' EXIT
                last=
                stale=0
                while :; do
                    beat=${'$'}(cat "${'$'}H" 2>/dev/null)
                    [ "${'$'}beat" = stopped ] && exit
                    if [ -z "${'$'}beat" ] || [ "${'$'}beat" = "${'$'}last" ]; then
                        stale=${'$'}((stale + 1))
                    else
                        stale=0
                    fi
                    [ "${'$'}stale" -ge 5 ] && break
                    last=${'$'}beat
                    sleep 1
                done
                if [ "${'$'}(settings get system fan_mode)" = ${FanController.CUSTOM} ]; then
                    ${FanController.vendorModeCommand(FanController.SMART)}
                fi
            """.trimIndent()
        }
    }
}
