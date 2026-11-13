package com.kei.pulse.root

import com.kei.pulse.data.GpuFloorController
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootCommandLimitTest {
    @Test
    fun `255 character command remains inline`() {
        val command = "x".repeat(255)
        val received = mutableListOf<String>()
        val result = RootSupport.runRootCommand(command, PrivilegedShell {
            received += it
            "result"
        })
        assertEquals(listOf(command), received)
        assertEquals("result", result)
    }

    @Test
    fun `256 character command runs intact from a temporary script and cleans up on failure`() {
        val command = "x".repeat(256)
        lateinit var script: File
        val result = RootSupport.runRootCommand(command, PrivilegedShell { invocation ->
            assertTrue(invocation.length <= 255)
            script = scriptFile(invocation)
            assertEquals(command, script.readText())
            null
        })
        assertEquals(null, result)
        assertFalse(script.exists())
    }

    @Test
    fun `GPU lock keeps both writes intact behind the transport limit`() {
        val root = "/sys/class/kgsl/kgsl-3d0"
        val commands = mutableListOf<String>()
        lateinit var script: File
        val transport = PrivilegedShell { command ->
            assertTrue(command.length <= 255)
            commands += command
            if (command.startsWith("sh ")) {
                script = scriptFile(command)
                assertEquals(
                    "chmod 666 $root/max_pwrlevel; chmod 666 $root/min_pwrlevel; " +
                        "echo 3 > $root/max_pwrlevel; echo 3 > $root/min_pwrlevel; " +
                        "chmod 444 $root/max_pwrlevel; chmod 444 $root/min_pwrlevel",
                    script.readText(),
                )
                ""
            } else {
                "3"
            }
        }
        val shell = PrivilegedShell { RootSupport.runRootCommand(it, transport) }
        assertEquals(3, GpuFloorController(shell).lockToCurrentCap(root))
        assertEquals(3, commands.size)
        assertFalse(script.exists())
    }

    private fun scriptFile(invocation: String): File {
        assertTrue(invocation.startsWith("sh '"))
        return File(invocation.removePrefix("sh '").removeSuffix("'").replace("'\\''", "'"))
    }
}
