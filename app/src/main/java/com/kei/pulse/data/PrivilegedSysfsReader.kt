package com.kei.pulse.data

fun interface PrivilegedSysfsReader {
    fun readText(path: String): String?

    /** Reads every path, in one privileged call where the implementation can. Missing or unreadable nodes map to null. */
    fun readAll(paths: List<String>): Map<String, String?> = paths.associateWith(::readText)
}
