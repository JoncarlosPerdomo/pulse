package com.kei.pulse.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CustomFanWatchdogTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun refreshStartsOnceAndStopLeavesTheLastHeartbeatToExpire() {
        val heartbeat = folder.newFile("heartbeat")
        var launches = 0
        var time = 1L
        val watchdog = CustomFanWatchdog(heartbeat, now = { time }) {
            launches++
            assertEquals("stopped", heartbeat.readText())
            true
        }
        assertTrue(watchdog.refresh())
        time++
        assertTrue(watchdog.refresh())
        assertEquals(1, launches)
        assertEquals("2", heartbeat.readText())
        watchdog.stop()
        watchdog.stop()
        assertEquals("2", heartbeat.readText())
        assertTrue(watchdog.refresh())
        assertEquals(2, launches)
    }

    @Test
    fun failedLaunchIsRetriedBeforeDriving() {
        val heartbeat = folder.newFile("heartbeat")
        var launches = 0
        val watchdog = CustomFanWatchdog(heartbeat) { ++launches > 1 }
        assertFalse(watchdog.refresh())
        assertEquals("stopped", heartbeat.readText())
        assertTrue(watchdog.refresh())
        assertEquals(2, launches)
    }

    @Test
    fun stalledDriverRestartsTheWatchdogBeforeResuming() {
        var time = 0L
        var launches = 0
        val watchdog = CustomFanWatchdog(folder.newFile(), now = { time }) {
            launches++
            true
        }
        watchdog.refresh()
        time = 4_000_000_000L
        watchdog.refresh()
        assertEquals(2, launches)
    }

    @Test
    fun scriptDetachesSerializesAndRestoresWithTheVendorCommand() {
        val script = CustomFanWatchdog.script("/data/user/0/com.kei.pulse/files/fan-heartbeat")
        assertTrue(script.contains("until mkdir \"\$L\""))
        assertTrue(script.contains("setsid sh \"\$0\" watch </dev/null >/dev/null 2>&1 &"))
        assertTrue(script.contains("[ \"\$stale\" -ge 5 ] && break"))
        assertTrue(script.contains("[ \"\$beat\" = stopped ] && exit"))
        assertTrue(script.contains("[ \"\$(settings get system fan_mode)\" = 6 ]"))
        assertTrue(script.contains(FanController.vendorModeCommand(FanController.SMART)))
        assertTrue(FanController.vendorModeCommand(FanController.SMART).length <= 255)
    }

    @Test
    fun scriptQuotesAppStoragePaths() {
        assertTrue(CustomFanWatchdog.script("/files/it's here").startsWith("H='/files/it'\"'\"'s here'"))
    }
}
