package com.kei.pulse.root

import android.content.Context
import com.kei.pulse.data.PrivilegedSysfsReader
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class PServerSysfsReader(
    private val context: Context,
) : PrivilegedSysfsReader {

    override fun readText(path: String): String? {
        return RootSupport.runRootCommand(
            command = "cat ${SysfsBatch.shellQuote(path)} 2>/dev/null",
        )?.trim()?.takeIf { it.isNotEmpty() }
    }

    override fun readAll(paths: List<String>): Map<String, String?> {
        if (paths.isEmpty()) return emptyMap()
        val script = runCatching { scripts.computeIfAbsent(paths, ::writeBatchScript) }.getOrNull()
            ?: return paths.associateWith { null }
        return SysfsBatch.parse(paths, RootSupport.runScript(script))
    }

    private fun writeBatchScript(paths: List<String>): File =
        RootSupport.writeScript(context, "sysfs-read-${scriptCount.incrementAndGet()}.sh", SysfsBatch.script(paths))

    private companion object {
        val scripts = ConcurrentHashMap<List<String>, File>()
        val scriptCount = AtomicInteger()
    }
}

/**
 * One PServer call that reads many sysfs nodes. PServer truncates commands longer than 255 characters
 * (and dies near 4 KB), and replies with only the first line of stdout. So the paths live in a script
 * file, each node is read with the `read` builtin (its first line, as a plain `cat` reply gives), and
 * values are joined by [RECORD] onto a single output line. Output uses mksh's `print` builtin because
 * Android's `printf` is a separate toybox process (~5 ms per call). Measured on the AYN Thor (Android 13):
 * https://github.com/JoncarlosPerdomo/pulse/pull/2
 */
internal object SysfsBatch {
    private const val RECORD = '\u0001'

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun script(paths: List<String>): String =
        "for f in ${paths.joinToString(" ", transform = ::shellQuote)}; do\n" +
            "  v=\n" +
            "  read -r v 2>/dev/null < \"\$f\"\n" +
            "  print -nr -- \"\$v\"\n" +
            "  print -n '\\001'\n" +
            "done\n"

    fun parse(paths: List<String>, reply: String?): Map<String, String?> {
        val values = reply?.split(RECORD)?.takeIf { it.size == paths.size + 1 }
        return paths.withIndex().associate { (index, path) ->
            path to values?.get(index)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
}
