package app.framex.core.shizuku

import app.framex.core.common.ShizukuState
import app.framex.core.optimization.GameModeLevel

object ShizukuStateResolver {
    const val MIN_SERVER_VERSION = 11

    fun resolve(installed: Boolean, binderAlive: Boolean, serverVersion: Int?, permissionGranted: Boolean, permissionDeniedForever: Boolean): ShizukuState = when {
        !binderAlive -> if (installed) ShizukuState.NotRunning else ShizukuState.NotInstalled
        serverVersion == null || serverVersion < MIN_SERVER_VERSION -> ShizukuState.Outdated
        permissionGranted -> ShizukuState.Ready
        permissionDeniedForever -> ShizukuState.PermissionDenied
        else -> ShizukuState.NeedsPermission
    }
}

/**
 * The complete list of privileged commands FrameX can run. There is no way to pass free text:
 * every command is built from a fixed template with validated inputs.
 */
object Commands {
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun isValidPackage(p: String) = p.length <= 255 && PACKAGE.matches(p)

    fun setGameMode(pkg: String, level: GameModeLevel): String? =
        if (isValidPackage(pkg)) "cmd game mode ${level.cmdName} $pkg" else null

    fun listGameModes(pkg: String): String? = if (isValidPackage(pkg)) "cmd game list-modes $pkg" else null

    fun forceStop(pkg: String): String? = if (isValidPackage(pkg)) "am force-stop $pkg" else null

    fun setBatterySaver(on: Boolean) = "settings put global low_power ${if (on) 1 else 0}"
    const val GET_BATTERY_SAVER = "settings get global low_power"
    const val LIST_LAYERS = "dumpsys SurfaceFlinger --list"

    /** Layer names come from SurfaceFlinger itself; reject anything that could break out of the quotes. */
    fun latency(layer: String): String? =
        if (layer.isNotEmpty() && layer.length <= 300 && layer.none { it == '\'' || it == '\n' || it == '\r' || it == '\u0000' }) "dumpsys SurfaceFlinger --latency '$layer'" else null
}

object Parsers {
    private val CURRENT_MODE = Regex("(?i)current(?:\\s+game)?\\s+mode\\s*[:=]\\s*([a-z_]+)")

    /** The mode that is *active*, not the list of available ones. Null if the output has no such field. */
    fun currentGameMode(output: String): String? = CURRENT_MODE.find(output)?.groupValues?.get(1)?.lowercase()

    fun batterySaverOn(output: String): Boolean? = when (output.trim()) { "1" -> true; "0" -> false; else -> null }
}
