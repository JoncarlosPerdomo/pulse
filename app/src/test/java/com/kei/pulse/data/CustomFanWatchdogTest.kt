package com.kei.pulse.data

import com.kei.pulse.data.CustomFanWatchdog.Action
import com.kei.pulse.data.CustomFanWatchdog.Arming
import com.kei.pulse.data.CustomFanWatchdog.State
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
    fun idleLaunches() {
        assertEquals(Action.Launch, CustomFanWatchdog.actionFor(State.Idle, 0))
    }

    @Test
    fun armedBeatsUntilTheScriptMayHaveGivenUp() {
        val stale = CustomFanWatchdog.STALE_AFTER_MS
        assertEquals(Action.Beat, CustomFanWatchdog.actionFor(State.Armed(0), stale - 1))
        assertEquals(Action.Launch, CustomFanWatchdog.actionFor(State.Armed(0), stale))
    }

    @Test
    fun backingOffWithholdsUntilTheRetryTime() {
        assertEquals(Action.Withhold, CustomFanWatchdog.actionFor(State.BackingOff(10), 9))
        assertEquals(Action.Launch, CustomFanWatchdog.actionFor(State.BackingOff(10), 10))
    }

    @Test
    fun nextArmsOnSuccessAndBacksOffOnFailure() {
        assertEquals(State.Armed(7), CustomFanWatchdog.next(State.Idle, 7, Arming.Succeeded))
        assertEquals(
            State.BackingOff(7 + CustomFanWatchdog.BACKOFF_MS),
            CustomFanWatchdog.next(State.Armed(0), 7, Arming.Failed),
        )
        assertEquals(State.BackingOff(10), CustomFanWatchdog.next(State.BackingOff(10), 7, null))
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
    fun slowTicksKeepOneWatcher() {
        var time = 0L
        var launches = 0
        val watchdog = CustomFanWatchdog(folder.newFile(), { time }) { launches++; CustomFanWatchdog.STARTED }
        repeat(10) {
            assertTrue(watchdog.startOrBeatBeforeDriving())
            time += SLOW_TICK_GAP_MS
        }
        assertEquals(1, launches)
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
    fun lateBeatRelaunches() {
        var time = 0L
        var launches = 0
        val watchdog = CustomFanWatchdog(folder.newFile(), { time }) { launches++; CustomFanWatchdog.STARTED }
        assertTrue(watchdog.startOrBeatBeforeDriving())
        time = CustomFanWatchdog.STALE_AFTER_MS
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
    fun staleLockHeldByALiveNonWatchdogPidDoesNotBlockTheWatcher() {
        val sandbox = ScriptSandbox()
        sandbox.lock.mkdir()
        File(sandbox.lock, "pid").writeText("1")
        sandbox.watchUntilExit()
        sandbox.assertRestoredOnceAndUnlocked()
    }

    @Test
    fun staleLockHeldByADeadPidDoesNotBlockTheWatcher() {
        val sandbox = ScriptSandbox()
        sandbox.lock.mkdir()
        File(sandbox.lock, "pid").writeText(deadPid().toString())
        sandbox.watchUntilExit()
        sandbox.assertRestoredOnceAndUnlocked()
    }

    @Test
    fun emptyLockDirectoryDoesNotBlockTheWatcher() {
        val sandbox = ScriptSandbox()
        sandbox.lock.mkdir()
        sandbox.watchUntilExit()
        sandbox.assertRestoredOnceAndUnlocked()
    }

    @Test
    fun emptyLockIsNotClearedWhileEachCheckSeesAFreshLock() {
        val sandbox = ScriptSandbox()
        val counter = quoted(File(sandbox.dir, "stat-count"))
        sandbox.stub("stat", "n=\$(cat $counter 2>/dev/null || echo 0); echo \$((n + 1)) > $counter; echo \$n")
        sandbox.lock.mkdir()
        val watcher = sandbox.watch()
        Thread.sleep(FRESH_LOCK_OBSERVATION_MS)
        assertTrue(watcher.isAlive)
        assertTrue(sandbox.lock.exists())
        watcher.destroyForcibly()
    }

    @Test
    fun relaunchedWatcherDisplacesTheRunningOne() {
        val sandbox = ScriptSandbox()
        sandbox.beating = true
        val first = sandbox.watch()
        sandbox.waitForLock()
        val second = sandbox.watch()
        assertTrue(first.waitFor(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(second.isAlive)
        sandbox.beating = false
        sandbox.waitForExit(second)
        sandbox.assertRestoredOnceAndUnlocked()
    }

    @Test
    fun successorCannotInterruptARestoreInProgress() {
        val sandbox = ScriptSandbox()
        sandbox.slowRestore = true
        val first = sandbox.watch()
        sandbox.waitForBounce()
        val second = sandbox.watch()
        sandbox.waitForExit(first)
        sandbox.waitForExit(second)
        assertEquals(FanController.SMART, sandbox.mode())
        sandbox.assertRestoredOnceAndUnlocked()
    }

    @Test
    fun watcherFinishesARestoreItsPredecessorLeftMidBounce() {
        val sandbox = ScriptSandbox(fanMode = FanController.bounceModeFor(FanController.SMART))
        sandbox.restoringMarker.createNewFile()
        sandbox.watchUntilExit()
        assertEquals(FanController.SMART, sandbox.mode())
        assertFalse(sandbox.restoringMarker.exists())
        sandbox.assertRestoredOnceAndUnlocked()
    }

    @Test
    fun restoreMarkerWithoutAMidBounceModeIsIgnored() {
        val sandbox = ScriptSandbox(fanMode = FanController.SPORT)
        sandbox.restoringMarker.createNewFile()
        sandbox.watchUntilExit()
        assertEquals(FanController.SPORT, sandbox.mode())
        assertFalse(sandbox.restoringMarker.exists())
        assertEquals(0, sandbox.restores())
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

    private fun quoted(file: File) = SysfsBatch.shellQuote(file.absolutePath)

    private fun deadPid(): Long =
        ProcessBuilder("sh", "-c", "echo $$").start().run { inputStream.bufferedReader().readText().trim().toLong() }

    private inner class ScriptSandbox(fanMode: Int = FanController.CUSTOM) {
        val dir: File = folder.newFolder()
        private val log = File(dir, "restored")
        private val modeFile = File(dir, "fan_mode").apply { writeText("$fanMode") }
        private val heartbeat = File(dir, "it's beat")
        private val beatingFlag = File(dir, "beating")
        private val slowRestoreFlag = File(dir, "slow-restore")
        private val bounced = File(dir, "bounced")
        private val bin = File(dir, "bin").apply { mkdir() }
        val lock = File(dir, "lock")
        val restoringMarker = File(dir, "lock.restoring")
        private val script = File(dir, "watchdog.sh").apply {
            writeText(CustomFanWatchdog.script(heartbeat.absolutePath, lock.absolutePath, TEST_STALE_AFTER_MS))
        }

        var beating: Boolean
            get() = beatingFlag.exists()
            set(value) { if (value) beatingFlag.createNewFile() else beatingFlag.delete() }

        var slowRestore: Boolean
            get() = slowRestoreFlag.exists()
            set(value) { if (value) slowRestoreFlag.createNewFile() else slowRestoreFlag.delete() }

        init {
            heartbeat.writeText("beat")
            stub("sleep", "[ -e ${quoted(beatingFlag)} ] && echo \$\$ > ${quoted(heartbeat)}; exec /bin/sleep 0.05")
            stub(
                "settings",
                """
                case "${'$'}1" in
                get) cat ${quoted(modeFile)};;
                put) if [ "${'$'}3" = fan_mode ]; then
                    echo "${'$'}4" > ${quoted(modeFile)}
                    [ "${'$'}4" = ${FanController.SMART} ] && echo restored >> ${quoted(log)}
                    if [ "${'$'}4" = ${FanController.bounceModeFor(FanController.SMART)} ]; then
                        : > ${quoted(bounced)}
                        [ -e ${quoted(slowRestoreFlag)} ] && /bin/sleep $SLOW_RESTORE_SECONDS
                    fi
                    fi;;
                esac
                """.trimIndent(),
            )
        }

        fun stub(name: String, body: String) {
            File(bin, name).apply {
                writeText("#!/bin/sh\n$body\n")
                setExecutable(true)
            }
        }

        fun mode(): Int = modeFile.readText().trim().toInt()

        fun watch(): Process = start("watch")

        fun launch(): String =
            start().let { it.waitFor(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS); it.inputStream.bufferedReader().readText() }

        fun watchUntilExit() = waitForExit(watch())

        fun waitForExit(process: Process) {
            assertTrue(process.waitFor(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
        }

        fun waitForLock() = waitUntil { File(lock, "pid").exists() }

        fun waitForBounce() = waitUntil { bounced.exists() }

        fun restores(): Int = if (log.exists()) log.readLines().size else 0

        fun waitForRestore() {
            waitUntil { restores() > 0 }
            assertEquals(1, restores())
        }

        fun assertRestoredOnceAndUnlocked() {
            assertEquals(1, restores())
            assertFalse(lock.exists())
        }

        private fun waitUntil(condition: () -> Boolean) {
            repeat(POLL_ATTEMPTS) { if (!condition()) Thread.sleep(POLL_PAUSE_MS) }
            assertTrue(condition())
        }

        private fun start(vararg args: String): Process =
            ProcessBuilder(listOf("sh", script.absolutePath) + args).redirectErrorStream(true).apply {
                environment()["PATH"] = "${bin.absolutePath}:${System.getenv("PATH")}"
            }.start()
    }

    private companion object {
        const val SCRIPT_TIMEOUT_SECONDS = 15L
        const val TEST_STALE_AFTER_MS = 300L
        const val SLOW_RESTORE_SECONDS = 1
        const val SLOW_TICK_GAP_MS = 4_000L
        const val FRESH_LOCK_OBSERVATION_MS = 600L
        const val POLL_ATTEMPTS = 200
        const val POLL_PAUSE_MS = 25L
    }
}
