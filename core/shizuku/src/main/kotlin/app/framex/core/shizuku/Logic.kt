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

    /**
     * Stops many apps with ONE command: the shell runs them in groups of 6 at the same time, so one slow app
     * does not hold up the rest and only one ADB stream is needed. Every name is validated first.
     */
    fun forceStopMany(pkgs: List<String>): String? {
        if (pkgs.isEmpty() || pkgs.size > 60 || !pkgs.all { isValidPackage(it) }) return null
        return "i=0; for p in ${pkgs.joinToString(" ")}; do am force-stop \"\$p\" & i=\$((i+1)); if [ \$((i % 6)) -eq 0 ]; then wait; fi; done; wait"
    }


    /** 0.3..0.9 in steps of 0.05, as the game-mode downscale intervention expects it ("0.7", "0.85"). */
    fun formatScale(scale: Float): String {
        val r = (Math.round(scale.coerceIn(0.3f, 0.9f) * 20) / 20.0)
        return String.format(java.util.Locale.US, "%.2f", r).trimEnd('0').trimEnd('.')
    }

    fun setDownscale(pkg: String, scale: Float): String? =
        if (isValidPackage(pkg)) "cmd game set --mode performance --downscale ${formatScale(scale)} $pkg" else null

    fun resetGameConfig(pkg: String): String? = if (isValidPackage(pkg)) "cmd game reset $pkg" else null

    fun setFixedPerformance(on: Boolean) = "cmd power set-fixed-performance-mode-enabled ${if (on) "true" else "false"}"

    const val GET_REFRESH = "settings get system min_refresh_rate; settings get system peak_refresh_rate"

    fun setRefreshRate(hz: Int): String? =
        if (hz in 30..240) "settings put system min_refresh_rate $hz; settings put system peak_refresh_rate $hz" else null

    private val SETTING_VALUE = Regex("^(null|[0-9]+(\\.[0-9]+)?)$")

    /** Puts the two refresh-rate overrides back exactly as they were ("null" = delete the override). */
    fun restoreRefreshRate(min: String, peak: String): String? {
        if (!SETTING_VALUE.matches(min) || !SETTING_VALUE.matches(peak)) return null
        fun one(key: String, v: String) = if (v == "null") "settings delete system $key" else "settings put system $key $v"
        return one("min_refresh_rate", min) + "; " + one("peak_refresh_rate", peak)
    }

    fun setWifiPerf(on: Boolean): String {
        val v = if (on) "enabled" else "disabled"
        return "cmd wifi force-hi-perf-mode $v; cmd wifi force-low-latency-mode $v"
    }

    const val GET_DATA_SAVER = "cmd netpolicy get restrict-background"
    fun setDataSaver(on: Boolean) = "cmd netpolicy set restrict-background ${if (on) "true" else "false"}"

    const val TIMESTATS_ENABLE = "dumpsys SurfaceFlinger --timestats -enable"
    const val TIMESTATS_DISABLE = "dumpsys SurfaceFlinger --timestats -disable"
    /** Two plain calls instead of combined flags, so it works however the flags are handled. */
    /** Only the busiest few layers: a much smaller dump for SurfaceFlinger to build while a game is running. */
    const val TIMESTATS_DUMP_CLEAR_LIGHT = "dumpsys SurfaceFlinger --timestats -dump -maxlayers 6; dumpsys SurfaceFlinger --timestats -clear"
    const val TIMESTATS_DUMP_CLEAR = "dumpsys SurfaceFlinger --timestats -dump; dumpsys SurfaceFlinger --timestats -clear"

    const val GET_WM_SIZE = "wm size"
    const val RESET_WM_SIZE = "wm size reset"
    fun setWmSize(w: Int, h: Int): String? = if (w in 320..4000 && h in 320..8000) "wm size ${w}x$h" else null
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

    /** Two lines: the min and the peak override, "null" when unset. */
    fun refreshSettings(output: String): Pair<String, String>? =
        output.lines().map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.size >= 2 }?.let { it[0] to it[1] }

    /** "Restrict background status: enabled" / "disabled" / "whitelisted". Only "enabled" means restricted. */
    fun dataSaverOn(output: String): Boolean? {
        val m = Regex("(?i)restrict background status:\\s*([a-z]+)").find(output)?.groupValues?.get(1)?.lowercase() ?: return null
        return when (m) { "enabled" -> true; "disabled", "whitelisted" -> false; else -> null }
    }

    /** "Physical size: 720x1640" and, if set, "Override size: 540x1230". */
    fun wmSize(output: String): app.framex.core.optimization.WmSize? {
        val phys = Regex("(?i)physical size:\\s*(\\d+)x(\\d+)").find(output)?.groupValues ?: return null
        val over = Regex("(?i)override size:\\s*(\\d+)x(\\d+)").find(output)?.groupValues
        return app.framex.core.optimization.WmSize(phys[1].toInt() to phys[2].toInt(), over?.let { it[1].toInt() to it[2].toInt() })
    }

    fun batterySaverOn(output: String): Boolean? = when (output.trim()) { "1" -> true; "0" -> false; else -> null }
}
