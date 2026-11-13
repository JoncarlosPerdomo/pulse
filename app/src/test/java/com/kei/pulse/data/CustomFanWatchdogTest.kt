package com.kei.pulse.data

import com.kei.pulse.data.CustomFanWatchdog.State
import com.kei.pulse.data.CustomFanWatchdog.Step
import com.kei.pulse.root.RootSupport
import com.kei.pulse.root.SysfsBatch
import java.io.File
import java.util.concurrent.TimeUnit
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
    fun timingContractLeavesRoomToRelaunchBeforeTheScriptRestores() {
        assertTrue(CustomFanWatchdog.POLL_INTERVAL_MS < CustomFanWatchdog.RELAUNCH_AFTER_MS)
        assertTrue(CustomFanWatchdog.RELAUNCH_AFTER_MS < CustomFanWatchdog.STALE_AFTER_MS)
    }

    @Test
    fun stepRelaunchesWhenIdleOrLate() {
        val late = CustomFanWatchdog.RELAUNCH_AFTER_MS
        assertEquals(Step.Relaunch, CustomFanWatchdog.step(State.Idle, 0))
        assertEquals(Step.Beat, CustomFanWatchdog.step(State.Armed(0), late - 1))
        assertEquals(Step.Relaunch, CustomFanWatchdog.step(State.Armed(0), late))
    }

    @Test
    fun stepWaitsOutTheBackoff() {
        assertEquals(Step.Wait, CustomFanWatchdog.step(State.BackingOff(10), 9))
        assertEquals(Step.Relaunch, CustomFanWatchdog.step(State.BackingOff(10), 10))
    }

    @Test
    fun beatsKeepOneWatcher() {
        val heartbeat = folder.newFile()
        var time = 1L
        var launches = 0
        val watchdog = CustomFanWatchdog(heartbeat, { time }) { launches++; CustomFanWatchdog.STARTED }
        assertTrue(watchdog.startOrBeatBeforeDriving())
        time++
        assertTrue(watchdog.startOrBeatBeforeDriving())
        assertEquals(1, launches)
        assertEquals("2", heartbeat.readText())
    }

    @Test
    fun stopLeavesTheHeartbeatToExpireAndTheNextBeatRelaunches() {
        val heartbeat = folder.newFile()
        var launches = 0
        val watchdog = CustomFanWatchdog(heartbeat, { 1L }) { launches++; CustomFanWatchdog.STARTED }
        assertTrue(watchdog.startOrBeatBeforeDriving())
        watchdog.stop()
        watchdog.stop()
        assertEquals("1", heartbeat.readText())
        assertTrue(watchdog.startOrBeatBeforeDriving())
        assertEquals(2, launches)
    }

    @Test
    fun lateBeatRelaunchesBeforeTheScriptRestores() {
        var time = 0L
        var launches = 0
        val watchdog = CustomFanWatchdog(folder.newFile(), { time }) { launches++; CustomFanWatchdog.STARTED }
        assertTrue(watchdog.startOrBeatBeforeDriving())
        time = CustomFanWatchdog.RELAUNCH_AFTER_MS
        assertTrue(watchdog.startOrBeatBeforeDriving())
        assertEquals(2, launches)
    }

    @Test
    fun failedLaunchBacksOffEvenAfterStop() {
        var time = 0L
        var launches = 0
        val watchdog = CustomFanWatchdog(folder.newFile(), { time }) {
            if (++launches == 1) null else CustomFanWatchdog.STARTED
        }
        assertFalse(watchdog.startOrBeatBeforeDriving())
        watchdog.stop()
        time = CustomFanWatchdog.BACKOFF_MS - 1
        assertFalse(watchdog.startOrBeatBeforeDriving())
        assertEquals(1, launches)
        time = CustomFanWatchdog.BACKOFF_MS
        assertTrue(watchdog.startOrBeatBeforeDriving())
        assertEquals(2, launches)
    }

    @Test
    fun slowLaunchDoesNotTriggerAnotherRelaunchOnTheNextTick() {
        var time = 0L
        var launches = 0
        val watchdog = CustomFanWatchdog(folder.newFile(), { time }) {
            launches++
            time += CustomFanWatchdog.RELAUNCH_AFTER_MS
            CustomFanWatchdog.STARTED
        }
        assertTrue(watchdog.startOrBeatBeforeDriving())
        time += CustomFanWatchdog.POLL_INTERVAL_MS
        assertTrue(watchdog.startOrBeatBeforeDriving())
        assertEquals(1, launches)
    }

    @Test
    fun unwritableHeartbeatDisallowsDrivingWithoutLaunching() {
        val watchdog = CustomFanWatchdog(File(folder.root, "missing/beat")) {
            error("Must not launch without a heartbeat")
        }
        assertFalse(watchdog.startOrBeatBeforeDriving())
    }

    @Test
    fun unreplaceableHeartbeatDisallowsDriving() {
        val watchdog = CustomFanWatchdog(folder.newFolder().apply { File(this, "x").createNewFile() }) {
            error("Must not launch without a heartbeat")
        }
        assertFalse(watchdog.startOrBeatBeforeDriving())
    }

    @Test
    fun launcherPrintsStartedForTheWatchdogToRecognize() {
        val sandbox = ScriptSandbox()
        assertEquals(CustomFanWatchdog.STARTED, sandbox.launch().trim())
        sandbox.waitForRestore()
    }

    @Test
    fun staleLockOwnersDoNotBlockTheWatcher() {
        for (owner in listOf("1", null)) {
            val sandbox = ScriptSandbox()
            sandbox.lock.mkdir()
            if (owner != null) File(sandbox.lock, "pid").writeText(owner)
            sandbox.watchUntilExit()
            assertEquals(1, sandbox.restores())
            assertFalse(sandbox.lock.exists())
        }
    }

    @Test
    fun relaunchedWatcherDisplacesTheRunningOne() {
        val sandbox = ScriptSandbox()
        sandbox.beating = true
        val first = sandbox.watch()
        sandbox.waitForLock()
        val second = sandbox.watch()
        assertTrue(first.waitFor(15, TimeUnit.SECONDS))
        assertTrue(second.isAlive)
        sandbox.beating = false
        sandbox.waitForExit(second)
        assertEquals(1, sandbox.restores())
        assertFalse(sandbox.lock.exists())
    }

    @Test
    fun watcherLeavesTheFanAloneWhenNotInCustomMode() {
        val sandbox = ScriptSandbox(fanMode = FanController.SMART)
        sandbox.watchUntilExit()
        assertEquals(0, sandbox.restores())
    }

    @Test
    fun restoreCommandFitsPServer() {
        assertTrue(FanController.vendorModeCommand(FanController.SMART).length <= RootSupport.PSERVER_COMMAND_LIMIT)
    }

    private inner class ScriptSandbox(fanMode: Int = FanController.CUSTOM) {
        private val dir = folder.newFolder()
        private val log = File(dir, "restored")
        private val heartbeat = File(dir, "it's beat")
        private val beatingFlag = File(dir, "beating")
        val lock = File(dir, "lock")
        private val script = File(dir, "watchdog.sh").apply {
            writeText(CustomFanWatchdog.script(heartbeat.absolutePath, lock.absolutePath))
        }
        private val path = stubs(fanMode)

        var beating: Boolean
            get() = beatingFlag.exists()
            set(value) { if (value) beatingFlag.createNewFile() else beatingFlag.delete() }

        init {
            heartbeat.writeText("beat")
        }

        private fun stubs(fanMode: Int): String {
            val bin = File(dir, "bin").apply { mkdir() }
            stub(bin, "sleep", "[ -e ${quoted(beatingFlag)} ] && echo \$\$ > ${quoted(heartbeat)}; exec /bin/sleep 0.05")
            stub(
                bin,
                "settings",
                "case \"\$1\" in get) echo $fanMode;; put) [ \"\$4\" = ${FanController.SMART} ] && echo restored >> ${quoted(log)};; esac",
            )
            return "${bin.absolutePath}:${System.getenv("PATH")}"
        }

        private fun quoted(file: File) = SysfsBatch.shellQuote(file.absolutePath)

        private fun stub(bin: File, name: String, body: String) {
            File(bin, name).apply {
                writeText("#!/bin/sh\n$body\n")
                setExecutable(true)
            }
        }

        fun watch(): Process = start("watch")

        fun launch(): String = start().let { it.waitFor(15, TimeUnit.SECONDS); it.inputStream.bufferedReader().readText() }

        fun watchUntilExit() = waitForExit(watch())

        fun waitForExit(process: Process) {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
        }

        fun waitForLock() {
            repeat(200) { if (!File(lock, "pid").exists()) Thread.sleep(25) }
            assertTrue(File(lock, "pid").exists())
        }

        fun restores(): Int = if (log.exists()) log.readLines().size else 0

        fun waitForRestore() {
            repeat(600) { if (restores() == 0) Thread.sleep(25) }
            assertEquals(1, restores())
        }

        private fun start(vararg args: String): Process =
            ProcessBuilder(listOf("sh", script.absolutePath) + args).redirectErrorStream(true).apply {
                environment()["PATH"] = path
            }.start()
    }
}
