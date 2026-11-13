package com.kei.pulse.root

internal object GpuClockCommands {
    fun write(path: String, value: Int): String =
        "if [ \"\$(cat $path)\" != $value ] || [ \"\$(stat -c %a $path)\" != 444 ]; then " +
            "chmod 666 $path; echo $value > $path; chmod 444 $path; fi"
}
