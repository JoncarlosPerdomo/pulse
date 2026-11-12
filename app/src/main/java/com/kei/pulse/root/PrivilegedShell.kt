package com.kei.pulse.root

/** Runs one privileged shell command and returns its stdout, or null when it failed. */
fun interface PrivilegedShell {
    fun run(command: String): String?
}

val PServerShell = PrivilegedShell(RootSupport::runRootCommand)
