package com.kei.pulse.root

import com.kei.pulse.data.GpuFloorController
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootCommandLimitTest {
    @Test
    fun `command at the PServer limit remains inline`() {
        val command = "x".repeat(RootSupport.PSERVER_COMMAND_LIMIT)
        val received = mutableListOf<String>()
        val result = RootSupport.runRootCommand(command, PrivilegedShell {
            received += it
            "result"
        })
        assertEquals(listOf(command), received)
        assertEquals("result", result)
    }

    @Test
    fun `command over the PServer limit runs intact from a temporary script and cleans up on failure`() {
        val command = "x".repeat(RootSupport.PSERVER_COMMAND_LIMIT + 1)
        lateinit var script: File
        val result = RootSupport.runRootCommand(command, PrivilegedShell { invocation ->
            assertTrue(invocation.length <= RootSupport.PSERVER_COMMAND_LIMIT)
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
            assertTrue(command.length <= RootSupport.PSERVER_COMMAND_LIMIT)
            commands += command
            if (command.startsWith("sh ")) {
                script = scriptFile(command)
                val contents = script.readText()
                for (node in listOf("max_pwrlevel", "min_pwrlevel")) {
                    assertTrue(contents.contains("echo 3 > $root/$node"))
                    assertTrue(contents.contains("chmod 444 $root/$node"))
                    assertTrue(contents.contains("if [ \"\$(cat $root/$node)\" != 3 ]"))
                }
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
