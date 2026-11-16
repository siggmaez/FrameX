package app.framex.core.shizuku

import kotlin.system.exitProcess

/** Runs inside Shizuku's shell/root process. Only ever receives commands built by [Commands]. */
class ShellUserService : IShellService.Stub() {
    override fun destroy() { exitProcess(0) }

    override fun exec(command: String): String {
        val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return "${p.waitFor()}\n$out"
    }
}
