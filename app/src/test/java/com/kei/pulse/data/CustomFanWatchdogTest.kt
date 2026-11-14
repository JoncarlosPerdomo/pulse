package com.kei.pulse.data

import com.kei.pulse.data.CustomFanWatchdog.Action
import com.kei.pulse.data.CustomFanWatchdog.LaunchOutcome
import com.kei.pulse.data.CustomFanWatchdog.State
import com.kei.pulse.data.CustomFanWatchdog.Status
import com.kei.pulse.root.RootSupport
import com.kei.pulse.root.SysfsBatch
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CustomFanWatchdogTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val watcherStamp by lazy { File(folder.root, "watcher-alive") }

    private val noStampRead: () -> Long? = { error("this decision must not read the watcher stamp") }

    @Test
    fun idleLaunches() {
        assertEquals(Action.Launch, CustomFanWatchdog.actionFor(State.Idle, 0, noStampRead))
    }

    @Test
    fun armedBeatsUntilTheScriptMayHaveGivenUp() {
        val stale = CustomFanWatchdog.STALE_AFTER_MS
        assertEquals(Action.Beat, CustomFanWatchdog.actionFor(State.Armed(0), stale - 1) { stale - 1 })
        assertEquals(Action.Launch, CustomFanWatchdog.actionFor(State.Armed(0), stale, noStampRead))
    }

    @Test
    fun armedRelaunchesOnceTheWatcherStopsStamping() {
        val now = 10_000L
        val stale = CustomFanWatchdog.WATCHER_STALE_AFTER_MS
        assertEquals(Action.Beat, CustomFanWatchdog.actionFor(State.Armed(now), now) { now - stale + 1 })
        assertEquals(Action.Launch, CustomFanWatchdog.actionFor(State.Armed(now), now) { now - stale })
        assertEquals(Action.Launch, CustomFanWatchdog.actionFor(State.Armed(now), now) { null })
    }

    @Test
    fun backingOffWithholdsUntilTheRetryTime() {
        assertEquals(Action.Withhold, CustomFanWatchdog.actionFor(State.BackingOff(10), 9, noStampRead))
        assertEquals(Action.Launch, CustomFanWatchdog.actionFor(State.BackingOff(10), 10, noStampRead))
    }

    @Test
    fun nextArmsOnSuccessAndBacksOffForTheStaleWindowOnFailure() {
        assertEquals(State.Armed(7), CustomFanWatchdog.next(State.Idle, 7, LaunchOutcome.Succeeded))
        assertEquals(
            State.BackingOff(7 + CustomFanWatchdog.STALE_AFTER_MS),
            CustomFanWatchdog.next(State.Armed(0), 7, LaunchOutcome.Failed),
        )
    }

    @Test
    fun beatsKeepOneWatcher() {
        val heartbeat = folder.newFile()
        var time = 1L
        var launches = 0
        val watchdog = CustomFanWatchdog(heartbeat, watcherStamp, { time }) { launches++; liveWatcherStarted() }
        assertArmed(watchdog.startOrBeatBeforeDriving())
        time++
        assertArmed(watchdog.startOrBeatBeforeDriving())
        assertEquals(1, launches)
        assertEquals("2", heartbeat.readText())
    }

    @Test
    fun slowTicksKeepOneWatcher() {
        var time = 0L
        var launches = 0
        val watchdog = CustomFanWatchdog(folder.newFile(), watcherStamp, { time }) { launches++; liveWatcherStarted() }
        repeat(10) {
            assertArmed(watchdog.startOrBeatBeforeDriving())
            time += SLOW_TICK_GAP_MS
        }
        assertEquals(1, launches)
    }

    @Test
    fun stopLeavesTheHeartbeatToExpireAndTheNextBeatRelaunches() {
        val heartbeat = folder.newFile()
        var launches = 0
        val watchdog = CustomFanWatchdog(heartbeat, watcherStamp, { 1L }) { launches++; liveWatcherStarted() }
        assertArmed(watchdog.startOrBeatBeforeDriving())
        watchdog.stop()
        watchdog.stop()
        assertEquals("1", heartbeat.readText())
        assertArmed(watchdog.startOrBeatBeforeDriving())
        assertEquals(2, launches)
    }

    @Test
    fun lateBeatRelaunches() {
        var time = 0L
        var launches = 0
        val watchdog = CustomFanWatchdog(folder.newFile(), watcherStamp, { time }) { launches++; liveWatcherStarted() }
        assertArmed(watchdog.startOrBeatBeforeDriving())
        time = CustomFanWatchdog.STALE_AFTER_MS
        assertArmed(watchdog.startOrBeatBeforeDriving())
        assertEquals(2, launches)
    }

    @Test
    fun stallBetweenSamplingAndWritingNearTheBoundaryRelaunches() {
        val times = ArrayDeque(
            listOf(0L, 0L, CustomFanWatchdog.STALE_AFTER_MS - 100, CustomFanWatchdog.STALE_AFTER_MS + 100),
        )
        var launches = 0
        val watchdog = CustomFanWatchdog(folder.newFile(), watcherStamp, { times.removeFirst() }) {
            launches++
            liveWatcherStarted()
        }
        assertArmed(watchdog.startOrBeatBeforeDriving())
        assertArmed(watchdog.startOrBeatBeforeDriving())
        assertEquals(2, launches)
    }

    @Test
    fun failedLaunchBacksOffOnceEvenAfterStop() {
        var time = 0L
        var launches = 0
        val watchdog = CustomFanWatchdog(folder.newFile(), watcherStamp, { time }) {
            if (++launches == 1) null else liveWatcherStarted()
        }
        assertEquals(Status.BackoffStarted, watchdog.startOrBeatBeforeDriving())
        watchdog.stop()
        time = CustomFanWatchdog.STALE_AFTER_MS - 1
        assertEquals(Status.BackingOff, watchdog.startOrBeatBeforeDriving())
        assertEquals(1, launches)
        time = CustomFanWatchdog.STALE_AFTER_MS
        assertArmed(watchdog.startOrBeatBeforeDriving())
        assertEquals(2, launches)
    }

    @Test
    fun launcherReportingStartedWithoutAWatcherStampBacksOff() {
        val watchdog = CustomFanWatchdog(folder.newFile(), watcherStamp, { 0L }) { CustomFanWatchdog.STARTED }
        assertEquals(Status.BackoffStarted, watchdog.startOrBeatBeforeDriving())
    }

    @Test
    fun watcherKilledWhileArmedIsRelaunched() {
        var time = 0L
        var launches = 0
        val watchdog = CustomFanWatchdog(folder.newFile(), watcherStamp, { time }) {
            launches++
            watcherStamp.writeText("$time")
            CustomFanWatchdog.STARTED
        }
        assertArmed(watchdog.startOrBeatBeforeDriving())
        time = CustomFanWatchdog.WATCHER_STALE_AFTER_MS
        assertArmed(watchdog.startOrBeatBeforeDriving())
        assertEquals(2, launches)
    }

    @Test
    fun unwritableHeartbeatBacksOffWithoutLaunching() {
        val watchdog = CustomFanWatchdog(File(folder.root, "missing/beat"), watcherStamp, { 0L }) {
            error("Must not launch without a heartbeat")
        }
        assertEquals(Status.BackoffStarted, watchdog.startOrBeatBeforeDriving())
    }

    @Test
    fun unreplaceableHeartbeatBacksOff() {
        val heartbeat = folder.newFolder().apply { File(this, "x").createNewFile() }
        val watchdog = CustomFanWatchdog(heartbeat, watcherStamp, { 0L }) {
            error("Must not launch without a heartbeat")
        }
        assertEquals(Status.BackoffStarted, watchdog.startOrBeatBeforeDriving())
    }

    @Test
    fun launcherPrintsStartedOnceTheWatcherIsUp() {
        val sandbox = ScriptSandbox()
        assertEquals(CustomFanWatchdog.STARTED, sandbox.launch().trim())
        sandbox.waitForRestore()
    }

    @Test
    fun watcherStampsItsLivenessOnTheBootClock() {
        val sandbox = ScriptSandbox()
        sandbox.beating = true
        val watcher = sandbox.watch()
        sandbox.waitForAck()
        assertTrue(CustomFanWatchdog.isWatcherLive(sandbox.watcherStampMs(), uptimeMs()))
        watcher.destroyForcibly()
    }

    @Test
    fun launcherDoesNotPrintStartedWhenTheWatcherCannotStart() {
        val sandbox = ScriptSandbox(lockName = "missing/lock")
        assertFalse(sandbox.launch().contains(CustomFanWatchdog.STARTED))
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
        sandbox.waitForTermination(first)
        sandbox.waitForExit(second)
        assertEquals(FanController.SMART, sandbox.mode())
        sandbox.assertRestoredOnceAndUnlocked()
    }

    @Test
    fun freshBeatAtTheStaleBoundaryCancelsTheRestore() {
        val sandbox = ScriptSandbox()
        sandbox.beatOnModeRead = true
        val watcher = sandbox.watch()
        Thread.sleep(FRESH_BEAT_OBSERVATION_MS)
        assertEquals(0, sandbox.restores())
        assertEquals(FanController.CUSTOM, sandbox.mode())
        watcher.destroyForcibly()
    }

    @Test
    fun watcherFinishesARestoreItsPredecessorLeftMidBounce() {
        val sandbox = ScriptSandbox(fanMode = FanController.bounceModeFor(FanController.SMART), staleAfterMs = MARKER_STALE_AFTER_MS)
        sandbox.leaveRestoreMarker(ageMs = 0)
        val watcher = sandbox.watch()
        sandbox.waitForAck()
        assertEquals(FanController.SMART, sandbox.mode())
        assertFalse(sandbox.restoringMarker.exists())
        assertEquals(1, sandbox.restores())
        watcher.destroyForcibly()
    }

    @Test
    fun oldRestoreMarkerDoesNotOverrideASilentModeTheUserChose() {
        val silent = FanController.bounceModeFor(FanController.SMART)
        val sandbox = ScriptSandbox(fanMode = silent, staleAfterMs = MARKER_STALE_AFTER_MS)
        sandbox.leaveRestoreMarker(ageMs = 2 * MARKER_STALE_AFTER_MS)
        val watcher = sandbox.watch()
        sandbox.waitForAck()
        assertEquals(silent, sandbox.mode())
        assertFalse(sandbox.restoringMarker.exists())
        assertEquals(0, sandbox.restores())
        watcher.destroyForcibly()
    }

    @Test
    fun restoreMarkerWithoutAMidBounceModeIsIgnored() {
        val sandbox = ScriptSandbox(fanMode = FanController.SPORT, staleAfterMs = MARKER_STALE_AFTER_MS)
        sandbox.leaveRestoreMarker(ageMs = 0)
        val watcher = sandbox.watch()
        sandbox.waitForAck()
        assertEquals(FanController.SPORT, sandbox.mode())
        assertFalse(sandbox.restoringMarker.exists())
        assertEquals(0, sandbox.restores())
        watcher.destroyForcibly()
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

    private fun assertArmed(status: Status) = assertTrue("expected Armed, was $status", status is Status.Armed)

    private fun liveWatcherStarted(): String {
        watcherStamp.writeText("${Long.MAX_VALUE}")
        return CustomFanWatchdog.STARTED
    }

    private fun quoted(file: File) = SysfsBatch.shellQuote(file.absolutePath)

    private fun deadPid(): Long =
        ProcessBuilder("sh", "-c", "echo $$").start().run { inputStream.bufferedReader().readText().trim().toLong() }

    private fun uptimeMs(): Long = (File("/proc/uptime").readText().substringBefore(' ').toDouble() * 1000).toLong()

    private inner class ScriptSandbox(
        fanMode: Int = FanController.CUSTOM,
        staleAfterMs: Long = TEST_STALE_AFTER_MS,
        lockName: String = "lock",
    ) {
        val dir: File = folder.newFolder()
        private val log = File(dir, "restored")
        private val modeFile = File(dir, "fan_mode").apply { writeText("$fanMode") }
        private val heartbeat = File(dir, "it's beat")
        private val alive = File(dir, "watcher's alive")
        private val beatingFlag = File(dir, "beating")
        private val beatOnModeReadFlag = File(dir, "beat-on-mode-read")
        private val slowRestoreFlag = File(dir, "slow-restore")
        private val bounced = File(dir, "bounced")
        private val bin = File(dir, "bin").apply { mkdir() }
        val lock = File(dir, lockName)
        val restoringMarker = File(dir, "$lockName.restoring")
        private val ack = File(dir, "$lockName.ack")
        private val script = File(dir, "watchdog.sh").apply {
            writeText(CustomFanWatchdog.script(heartbeat.absolutePath, alive.absolutePath, lock.absolutePath, staleAfterMs))
        }

        var beating: Boolean
            get() = beatingFlag.exists()
            set(value) { if (value) beatingFlag.createNewFile() else beatingFlag.delete() }

        var beatOnModeRead: Boolean
            get() = beatOnModeReadFlag.exists()
            set(value) { if (value) beatOnModeReadFlag.createNewFile() else beatOnModeReadFlag.delete() }

        var slowRestore: Boolean
            get() = slowRestoreFlag.exists()
            set(value) { if (value) slowRestoreFlag.createNewFile() else slowRestoreFlag.delete() }

        init {
            assumeTrue(File("/proc/uptime").exists())
            heartbeat.writeText("beat")
            stub("sleep", "[ -e ${quoted(beatingFlag)} ] && echo \$\$ > ${quoted(heartbeat)}; exec /bin/sleep 0.05")
            stub(
                "settings",
                """
                case "${'$'}1" in
                get)
                    [ -e ${quoted(beatOnModeReadFlag)} ] && echo "${'$'}${'$'}-${'$'}(date +%s%N)" > ${quoted(heartbeat)}
                    cat ${quoted(modeFile)};;
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

        fun leaveRestoreMarker(ageMs: Long) = restoringMarker.writeText("${uptimeMs() - ageMs}")

        fun mode(): Int = modeFile.readText().trim().toInt()

        fun watcherStampMs(): Long? = alive.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()

        fun watch(): Process = start("watch")

        fun launch(): String =
            start().let { it.waitFor(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS); it.inputStream.bufferedReader().readText() }

        fun watchUntilExit() = waitForExit(watch())

        fun waitForExit(process: Process) {
            waitForTermination(process)
            assertEquals(0, process.exitValue())
        }

        fun waitForTermination(process: Process) = assertTrue(process.waitFor(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS))

        fun waitForLock() = waitUntil { File(lock, "pid").exists() }

        fun waitForBounce() = waitUntil { bounced.exists() }

        fun waitForAck() = waitUntil { ack.exists() }

        fun restores(): Int = if (log.exists()) log.readLines().size else 0

        fun waitForRestore() {
            waitUntil { restores() > 0 }
            assertEquals(1, restores())
        }

        fun assertRestoredOnceAndUnlocked() {
            assertEquals(1, restores())
            assertFalse(lock.exists())
            assertFalse(ack.exists())
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
        const val MARKER_STALE_AFTER_MS = 10_000L
        const val SLOW_RESTORE_SECONDS = "0.5"
        const val SLOW_TICK_GAP_MS = 4_000L
        const val FRESH_LOCK_OBSERVATION_MS = 600L
        const val FRESH_BEAT_OBSERVATION_MS = 1_000L
        const val POLL_ATTEMPTS = 200
        const val POLL_PAUSE_MS = 25L
    }
}
