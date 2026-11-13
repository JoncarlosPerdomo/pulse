package com.kei.pulse.root

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object RootSupport {

    const val PSERVER_COMMAND_LIMIT = 255

    // Process-wide serialization of PServer access. The binder cannot service overlapping
    // transacts reliably, and several callers (per-app watcher apply, telemetry poll, tile,
    // sleep monitor) hit it concurrently. Every PServer path — cat() reads, batched sysfs reads
    // and generated scripts via runScript — funnels through runRootCommand, so locking here
    // serializes all of them at command granularity. Each command is short and all callers
    // run on Dispatchers.IO, so a blocking lock is fine. runScript does not lock itself, so
    // there is no re-entrancy.
    private val pServerLock = ReentrantLock()

    fun runRootCommand(command: String): String? =
        runRootCommand(command, PrivilegedShell { RootExec().executeAsRoot(it).getOrNull() })

    internal fun runRootCommand(command: String, shell: PrivilegedShell): String? = pServerLock.withLock {
        if (command.length <= PSERVER_COMMAND_LIMIT) return shell.run(command)

        // PServer truncates inline commands at 255 characters on the Thor:
        // https://github.com/JoncarlosPerdomo/pulse/issues/4
        val script = File.createTempFile("pulse-", ".sh")
        try {
            writeScript(script, command)
            val invocation = "sh ${SysfsBatch.shellQuote(script.absolutePath)}"
            require(invocation.length <= PSERVER_COMMAND_LIMIT) { "PServer script path exceeds its command limit" }
            shell.run(invocation)
        } finally {
            script.delete()
        }
    }

    fun runGeneratedScript(
        context: Context,
        scriptName: String,
        scriptContents: String,
    ): String? = runScript(writeScript(context, scriptName, scriptContents))

    fun runScript(scriptFile: File): String? = runRootCommand("sh ${scriptFile.absolutePath}")

    fun writeScript(
        context: Context,
        scriptName: String,
        scriptContents: String,
    ): File {
        val scriptDir = File(context.filesDir, "root-scripts")
        if (!scriptDir.exists()) {
            scriptDir.mkdirs()
        }

        return writeScript(File(scriptDir, scriptName), scriptContents)
    }

    // PServer runs as a different uid and must read the app's generated scripts:
    // https://github.com/JoncarlosPerdomo/pulse/pull/2
    @android.annotation.SuppressLint("SetWorldReadable")
    private fun writeScript(scriptFile: File, scriptContents: String): File {
        val staged = File.createTempFile("${scriptFile.name}.", ".new", scriptFile.parentFile)
        staged.writeText(scriptContents)
        staged.setReadable(true, false)
        staged.setExecutable(true, false)
        Files.move(staged.toPath(), scriptFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        return scriptFile
    }
}
