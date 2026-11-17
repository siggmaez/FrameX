package app.framex.core.shizuku

import app.framex.core.common.ShizukuState
import app.framex.core.optimization.GameModeLevel
import app.framex.core.optimization.PrivilegedShell
import app.framex.core.optimization.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The typed privileged operations, shared by every way of getting shell access (Shizuku, wireless debugging).
 * Each operation builds its command from a fixed template with validated input; there is no free-form command.
 */
abstract class TypedShell : PrivilegedShell {
    protected abstract suspend fun exec(command: String?): ShellResult

    override suspend fun setGameMode(pkg: String, level: GameModeLevel) = exec(Commands.setGameMode(pkg, level))

    override suspend fun getGameMode(pkg: String): String? =
        exec(Commands.listGameModes(pkg)).takeIf { it.ok }?.let { Parsers.currentGameMode(it.output) }

    override suspend fun forceStop(pkg: String) = exec(Commands.forceStop(pkg))

    override suspend fun forceStopMany(pkgs: List<String>) = exec(Commands.forceStopMany(pkgs))


    override suspend fun setDownscale(pkg: String, scale: Float) = exec(Commands.setDownscale(pkg, scale))
    override suspend fun resetGameConfig(pkg: String) = exec(Commands.resetGameConfig(pkg))
    override suspend fun setFixedPerformance(on: Boolean) = exec(Commands.setFixedPerformance(on))
    override suspend fun refreshRateSettings(): Pair<String, String>? =
        exec(Commands.GET_REFRESH).takeIf { it.ok }?.let { Parsers.refreshSettings(it.output) }
    override suspend fun setRefreshRate(hz: Int) = exec(Commands.setRefreshRate(hz))
    override suspend fun restoreRefreshRate(min: String, peak: String) = exec(Commands.restoreRefreshRate(min, peak))

    override suspend fun setWifiPerf(on: Boolean) = exec(Commands.setWifiPerf(on))
    override suspend fun backgroundDataRestricted(): Boolean? =
        exec(Commands.GET_DATA_SAVER).takeIf { it.ok }?.let { Parsers.dataSaverOn(it.output) }
    override suspend fun setBackgroundDataRestricted(on: Boolean) = exec(Commands.setDataSaver(on))

    override suspend fun timeStatsEnable() = exec(Commands.TIMESTATS_ENABLE)
    override suspend fun timeStatsDumpClear(light: Boolean) = exec(if (light) Commands.TIMESTATS_DUMP_CLEAR_LIGHT else Commands.TIMESTATS_DUMP_CLEAR)
    override suspend fun timeStatsDisable() = exec(Commands.TIMESTATS_DISABLE)
    override suspend fun setBatterySaver(on: Boolean) = exec(Commands.setBatterySaver(on))

    override suspend fun isBatterySaverOn(): Boolean? =
        exec(Commands.GET_BATTERY_SAVER).takeIf { it.ok }?.let { Parsers.batterySaverOn(it.output) }

    override suspend fun surfaceFlingerLayers(): List<String>? =
        exec(Commands.LIST_LAYERS).takeIf { it.ok }?.output?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }

    override suspend fun surfaceFlingerLatency(layer: String): String? =
        exec(Commands.latency(layer)).takeIf { it.ok }?.output
}

/** Shell access through the Shizuku user service. */
@Singleton
class ShizukuShell @Inject constructor(private val manager: ShizukuManager) : TypedShell() {

    override fun isAvailable() = manager.status.value.let { it.state == ShizukuState.Ready && it.serviceConnected }

    override suspend fun exec(command: String?): ShellResult {
        if (command == null) return ShellResult(false, "rejected: invalid input")
        val s = manager.shellService() ?: return ShellResult(false, "Shizuku is not connected")
        return withContext(Dispatchers.IO) {
            try {
                withTimeout(20_000) {
                    val raw = s.exec(command)
                    ShellResult((raw.substringBefore('\n').toIntOrNull() ?: -1) == 0, raw.substringAfter('\n', "").trim())
                }
            } catch (e: Exception) {
                ShellResult(false, e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
