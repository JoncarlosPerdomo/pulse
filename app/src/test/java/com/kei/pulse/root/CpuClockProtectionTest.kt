package com.kei.pulse.root

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CpuClockProtectionTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var state: File
    private val owners = mutableListOf<Process>()

    @Before fun setUp() {
        assumeTrue(File("/proc/self/stat").isFile)
        state = File(folder.root, "state")
    }

    @After fun cleanUp() {
        owners.forEach { it.destroyForcibly().waitFor() }
        if (::state.isInitialized) {
            File(state, "watcher").takeIf { it.isFile }?.readText()?.substringBefore(' ')
                ?.trim()?.toLongOrNull()?.let { ProcessHandle.of(it).ifPresent { process -> process.destroyForcibly() } }
        }
    }

    @Test fun `same request preserves a higher effective minimum without another write`() {
        val minimum = node("policy0", "scaling_min_freq", "300")
        val request = CpuClockProtection.write(minimum.path, 500, protect = true)
        assertEquals(0, run(request).first)
        assertEquals("r--r--r--", permissions(minimum))
        setPermissions(minimum, "rw-rw-rw-")
        minimum.writeText("700")
        setPermissions(minimum, "r--r--r--")
        val modified = minimum.lastModified()

        assertEquals(0, run(request).first)

        assertEquals("700", minimum.readText())
        assertEquals(modified, minimum.lastModified())
    }

    @Test fun `release restores original permissions without replaying the original frequency`() {
        val minimum = node("policy0", "scaling_min_freq", "300", "rw-rw----")
        assertEquals(0, run(CpuClockProtection.write(minimum.path, 500, true)).first)
        assertEquals("r--r-----", permissions(minimum))

        assertEquals(0, run(CpuClockProtection.release(minimum.path)).first)

        assertEquals("rw-rw----", permissions(minimum))
        assertEquals("500\n", minimum.readText())
    }

    @Test fun `an uncapped CPU retains a higher vendor boost and releases its permission lock`() {
        val maximum = node("policy0", "scaling_max_freq", "2000")
        assertEquals(0, run(CpuClockProtection.write(maximum.path, 1000, true)).first)
        setPermissions(maximum, "rw-rw-rw-")
        maximum.writeText("2200")
        setPermissions(maximum, "r--r--r--")

        assertEquals(0, run(CpuClockProtection.write(maximum.path, 2000, false)).first)

        assertEquals("2200", maximum.readText())
        assertEquals("rw-r--r--", permissions(maximum))
    }

    @Test fun `foreign protection rejects acquisition and rolls back new permission ownership`() {
        val minimum = node("policy0", "scaling_min_freq", "300")
        val foreign = node("policy3", "scaling_min_freq", "400", "r--r--r--")
        val result = run(listOf(
            CpuClockProtection.write(minimum.path, 500, true),
            CpuClockProtection.write(foreign.path, 600, true),
        ).joinToString("\n"))

        assertTrue(result.first != 0)
        assertTrue(result.second.contains(CpuClockProtection.ERROR))
        assertEquals("rw-r--r--", permissions(minimum))
        assertEquals("r--r--r--", permissions(foreign))
        assertEquals("400", foreign.readText())
    }

    @Test fun `a suspended owner retains protection and process death releases it`() {
        val owner = owner()
        val minimum = node("policy0", "scaling_min_freq", "300")
        assertEquals(0, run(CpuClockProtection.write(minimum.path, 500, true), owner.pid).first)
        ProcessBuilder("kill", "-STOP", owner.pid.toString()).start().waitFor()
        Thread.sleep(1_200)
        assertEquals("r--r--r--", permissions(minimum))

        owner.process.destroyForcibly().waitFor()

        await { permissions(minimum) == "rw-r--r--" }
        assertEquals("500\n", minimum.readText())
    }

    @Test fun `replacement process recovers ownership when both owner and watchdog died`() {
        val owner = owner()
        val minimum = node("policy0", "scaling_min_freq", "300")
        assertEquals(0, run(CpuClockProtection.write(minimum.path, 500, true), owner.pid).first)
        val watcher = File(state, "watcher").readText().substringBefore(' ').toLong()
        ProcessHandle.of(watcher).get().destroyForcibly()
        await { !ProcessHandle.of(watcher).map { it.isAlive }.orElse(false) || zombie(watcher) }
        owner.process.destroyForcibly().waitFor()
        assertEquals("r--r--r--", permissions(minimum))

        assertEquals(0, run("").first)

        assertEquals("rw-r--r--", permissions(minimum))
        assertFalse(File(state, "owner").exists())
    }

    @Test fun `recovery preserves unrelated permission changes`() {
        val owner = owner()
        val minimum = node("policy0", "scaling_min_freq", "300")
        assertEquals(0, run(CpuClockProtection.write(minimum.path, 500, true), owner.pid).first)
        setPermissions(minimum, "rw-r-----")

        owner.process.destroyForcibly().waitFor()

        await { !File(state, "owner").exists() }
        assertEquals("rw-r-----", permissions(minimum))
    }

    @Test fun `recovery ignores an interrupted journal staging file and restores an unlocked write`() {
        val owner = owner()
        val minimum = node("policy0", "scaling_min_freq", "300")
        assertEquals(0, run(CpuClockProtection.write(minimum.path, 500, true), owner.pid).first)
        val watcher = File(state, "watcher").readText().substringBefore(' ').toLong()
        ProcessHandle.of(watcher).get().destroyForcibly()
        await { !ProcessHandle.of(watcher).map { it.isAlive }.orElse(false) || zombie(watcher) }
        owner.process.destroyForcibly().waitFor()
        setPermissions(minimum, "rw-rw-rw-")
        File(state, "node-policy0-scaling_min_freq.next").writeText("unfinished")

        assertEquals(0, run("").first)

        assertEquals("rw-r--r--", permissions(minimum))
        assertFalse(File(state, "node-policy0-scaling_min_freq.next").exists())
    }

    @Test fun `a surviving old watchdog cannot stand in for the replacement owner's watchdog`() {
        val oldOwner = owner()
        val minimum = node("policy0", "scaling_min_freq", "300")
        val request = CpuClockProtection.write(minimum.path, 500, true)
        assertEquals(0, run(request, oldOwner.pid).first)
        val oldWatcher = File(state, "watcher").readText().substringBefore(' ').toLong()
        try {
            ProcessBuilder("kill", "-STOP", oldWatcher.toString()).start().waitFor()
            oldOwner.process.destroyForcibly().waitFor()
            val replacement = owner()

            assertEquals(0, run(request, replacement.pid).first)
            assertTrue(File(state, "watcher").readText().substringBefore(' ').toLong() != oldWatcher)
            replacement.process.destroyForcibly().waitFor()

            await { permissions(minimum) == "rw-r--r--" }
        } finally {
            ProcessHandle.of(oldWatcher).ifPresent { it.destroyForcibly() }
        }
    }

    @Test fun `an interrupted lock owner write does not block recovery permanently`() {
        assertEquals(0, run("").first)
        File(state, "lock").mkdir()
        File(state, "lock/owner").writeText("")

        assertEquals(0, run("").first)
        assertFalse(File(state, "lock").exists())
    }

    private fun node(policy: String, name: String, value: String, mode: String = "rw-r--r--"): File =
        File(File(folder.root, policy).apply { mkdirs() }, name).apply {
            writeText(value)
            setPermissions(this, mode)
        }

    private fun run(body: String, ownerPid: Long = ProcessHandle.current().pid()): Pair<Int, String> {
        val script = folder.newFile().apply {
            writeText(CpuClockProtection.script(body, state.path, ownerPid))
        }
        val process = ProcessBuilder("sh", script.path).redirectErrorStream(true).start()
        assertTrue("CPU transaction timed out", process.waitFor(10, TimeUnit.SECONDS))
        return process.exitValue() to process.inputStream.bufferedReader().readText()
    }

    private data class Owner(val process: Process, val pid: Long)

    private fun owner(): Owner {
        val process = ProcessBuilder("sh", "-c", "echo \$\$; exec sleep 60").start()
        owners += process
        return Owner(process, process.inputStream.bufferedReader().readLine().toLong())
    }

    private fun permissions(file: File): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()))

    private fun setPermissions(file: File, mode: String) {
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(mode))
    }

    private fun zombie(pid: Long): Boolean = File("/proc/$pid/stat").takeIf { it.isFile }
        ?.readText()?.substringAfterLast(") ")?.startsWith("Z ") == true

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("CPU recovery did not complete", condition())
    }
}
