package com.kei.pulse.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelemetryReaderTest {

    private class FirstBatchFailsReader(private val nodes: Map<String, String>) : PrivilegedSysfsReader {
        private var failNext = true

        override fun readText(path: String): String? = nodes[path]

        override fun readAll(paths: List<String>): Map<String, String?> {
            if (!failNext) return super.readAll(paths)
            failNext = false
            return paths.associateWith { null }
        }
    }

    @Test
    fun failedThermalZoneScanIsRetriedOnTheNextRead() {
        val zone = "/sys/class/thermal/thermal_zone3"
        val reader = TelemetryReader(
            FirstBatchFailsReader(mapOf("$zone/type" to "cpuss-0", "$zone/temp" to "61900")),
        )

        assertNull(reader.read(emptyList()).cpuTempC)
        assertEquals(61, reader.read(emptyList()).cpuTempC)
    }
}
