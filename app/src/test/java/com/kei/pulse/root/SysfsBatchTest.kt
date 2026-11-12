package com.kei.pulse.root

import org.junit.Assert.assertEquals
import org.junit.Test

class SysfsBatchTest {

    private val paths = listOf(
        "/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq",
        "/sys/class/kgsl/kgsl-3d0/gpuclk",
        "/sys/class/power_supply/battery/status",
    )

    private fun reply(vararg values: String) = values.joinToString("") { "$it\u0001" }

    @Test
    fun valuesMapToTheirPathsInOrder() {
        assertEquals(
            mapOf(
                paths[0] to "2016000",
                paths[1] to "300000000",
                paths[2] to "Charging",
            ),
            SysfsBatch.parse(paths, reply("2016000", "300000000", "Charging")),
        )
    }

    @Test
    fun emptyOrBlankValuesAreNullAndOthersAreTrimmed() {
        assertEquals(
            mapOf(
                paths[0] to null,
                paths[1] to "12 %",
                paths[2] to null,
            ),
            SysfsBatch.parse(paths, reply("", "  12 %  ", "   ")),
        )
    }

    @Test
    fun missingReplyMakesEveryPathNull() {
        assertEquals(paths.associateWith { null }, SysfsBatch.parse(paths, null))
    }

    @Test
    fun recordCountMismatchMakesEveryPathNull() {
        assertEquals(paths.associateWith { null }, SysfsBatch.parse(paths, reply("2016000", "300000000")))
        assertEquals(paths.associateWith { null }, SysfsBatch.parse(paths, "2016000"))
    }

    @Test
    fun scriptQuotesEachPathForTheShell() {
        val script = SysfsBatch.script(listOf("/sys/a", "/sys/it's"))
        assertEquals(
            "for f in '/sys/a' '/sys/it'\\''s'; do\n" +
                "  v=\n" +
                "  read -r v 2>/dev/null < \"\$f\"\n" +
                "  print -nr -- \"\$v\"\n" +
                "  print -n '\\001'\n" +
                "done\n",
            script,
        )
    }
}
