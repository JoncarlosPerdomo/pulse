package com.kei.pulse.root

object CpuClockProtection {
    const val SUCCESS = "PULSE_CPU_OK"
    const val ERROR = "PULSE_CPU_ERROR"
    internal const val STATE_DIRECTORY = "/data/local/tmp/pulse-cpu-clock"

    private val program by lazy {
        checkNotNull(CpuClockProtection::class.java.getResourceAsStream("/cpu-clock.sh"))
            .bufferedReader().use { it.readText() }
    }

    fun write(path: String, value: Int, protect: Boolean): String =
        "cpu_write ${SysfsBatch.shellQuote(path)} $value ${if (protect) 1 else 0}"

    fun release(path: String): String = "cpu_release ${SysfsBatch.shellQuote(path)}"

    fun script(
        commands: String,
        stateDirectory: String = STATE_DIRECTORY,
        ownerPid: Long? = null,
    ): String = buildString {
        appendLine("CPU_STATE=${SysfsBatch.shellQuote(stateDirectory)}")
        appendLine("CPU_OWNER_PID=${ownerPid ?: "\$(pidof com.kei.pulse || true)"}")
        appendLine(program)
        appendLine("cpu_begin")
        appendLine(commands)
    }
}
