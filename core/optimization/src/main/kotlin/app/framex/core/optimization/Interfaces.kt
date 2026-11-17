package app.framex.core.optimization

import app.framex.core.common.OptAction
import app.framex.core.datastore.EngineSnapshot

interface ActionExecutor {
    /** Applies [action] and validates the result by reading the state back where Android allows it. */
    suspend fun apply(action: OptAction, request: ApplyRequest): ExecResult
    suspend fun restore(action: OptAction, token: String?): ExecResult
}

interface CapabilityProvider { fun detect(): Map<OptAction, Capability> }

interface EngineStorage {
    suspend fun load(): EngineSnapshot?
    suspend fun save(snapshot: EngineSnapshot)
    suspend fun clear()
}

interface OperationLogger {
    suspend fun log(profile: String, action: OptAction, status: ResultStatus, message: String)
}

data class ShellResult(val ok: Boolean, val output: String)

/**
 * The only privileged surface. Typed operations, not a free-form terminal.
 * Implemented by the Shizuku integration; the default says "unavailable".
 */
interface PrivilegedShell {
    fun isAvailable(): Boolean

    /** Brings the privileged link back if it was set up before and dropped. Default: nothing to do. */
    suspend fun ensureReady() {}

    suspend fun setGameMode(pkg: String, level: GameModeLevel): ShellResult
    suspend fun getGameMode(pkg: String): String?
    suspend fun forceStop(pkg: String): ShellResult
    suspend fun forceStopMany(pkgs: List<String>): ShellResult
    /** Game-mode render resolution for one game (Android 13+). */
    suspend fun setDownscale(pkg: String, scale: Float): ShellResult
    suspend fun resetGameConfig(pkg: String): ShellResult
    suspend fun setFixedPerformance(on: Boolean): ShellResult
    /** "min|peak" refresh-rate overrides currently stored ("null" = not set), or null if unreadable. */
    suspend fun refreshRateSettings(): Pair<String, String>?
    suspend fun setRefreshRate(hz: Int): ShellResult
    /** Wi-Fi high-performance + low-latency test modes (no power-saving dips on the radio). */
    suspend fun setWifiPerf(on: Boolean): ShellResult
    /** Data Saver: true = background data of apps is restricted. Null if unreadable. */
    suspend fun backgroundDataRestricted(): Boolean?
    suspend fun setBackgroundDataRestricted(on: Boolean): ShellResult
    suspend fun restoreRefreshRate(min: String, peak: String): ShellResult
    suspend fun setBatterySaver(on: Boolean): ShellResult
    suspend fun isBatterySaverOn(): Boolean?
    suspend fun surfaceFlingerLayers(): List<String>?
    suspend fun surfaceFlingerLatency(layer: String): String?
    /** SurfaceFlinger TimeStats: per-layer presented-frame statistics. Works on newer Android where --latency can stay empty. */
    suspend fun timeStatsEnable(): ShellResult
    /** Dump, then clear, so each call covers only the time since the previous one. */
    suspend fun timeStatsDumpClear(): String?
    suspend fun timeStatsDisable(): ShellResult
}
