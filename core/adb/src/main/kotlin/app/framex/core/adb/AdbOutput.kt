package app.framex.core.adb

import app.framex.core.optimization.ShellResult

/** The ADB shell service does not report an exit code, so every command is followed by a marker line with `$?`. */
object AdbOutput {
    const val MARK = "__FX_EXIT:"

    fun wrap(command: String) = "$command; echo $MARK\$?"

    fun parse(raw: String): ShellResult {
        val i = raw.lastIndexOf(MARK)
        if (i < 0) return ShellResult(false, raw.trim())
        val code = raw.substring(i + MARK.length).trim().toIntOrNull()
        return ShellResult(code == 0, raw.substring(0, i).trim())
    }
}
