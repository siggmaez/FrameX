package app.framex.core.optimization

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import app.framex.core.common.OptAction
import app.framex.core.database.OptimizationLogRepository
import app.framex.core.datastore.EngineSnapshot
import app.framex.core.datastore.EngineStateStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidCapabilityProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shell: PrivilegedShell,
) : CapabilityProvider {
    override fun detect(): Map<OptAction, Capability> {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return CapabilityEvaluator.evaluate(
            nm.isNotificationPolicyAccessGranted, shell.isAvailable(), Build.VERSION.SDK_INT,
            canWriteSettings = android.provider.Settings.System.canWrite(context),
            wifiSupported = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI),
        )
    }
}

class StoreStorage(private val store: EngineStateStore) : EngineStorage {
    override suspend fun load() = store.load()
    override suspend fun save(snapshot: EngineSnapshot) = store.save(snapshot)
    override suspend fun clear() = store.clear()
}

class RoomLogger(private val repo: OptimizationLogRepository) : OperationLogger {
    override suspend fun log(profile: String, action: OptAction, status: ResultStatus, message: String) =
        repo.log(profile, action.name, status.name, message)
}

/** Do Not Disturb: reversible, needs the user to grant "Do Not Disturb access" to FrameX. */
class DndExecutor(private val context: Context) : ActionExecutor {
    private val nm get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun apply(action: OptAction, request: ApplyRequest): ExecResult {
        if (!nm.isNotificationPolicyAccessGranted) return ExecResult(ResultStatus.Failed, context.getString(R.string.opt_dnd_no_access))
        val previous = nm.currentInterruptionFilter
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        val ok = nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY
        return if (ok) ExecResult(ResultStatus.Applied, context.getString(R.string.opt_dnd_on), previous.toString())
        else ExecResult(ResultStatus.Failed, context.getString(R.string.opt_dnd_not_confirmed))
    }

    override suspend fun restore(action: OptAction, token: String?): ExecResult {
        val prev = token?.toIntOrNull() ?: return ExecResult(ResultStatus.Failed, context.getString(R.string.opt_no_previous))
        nm.setInterruptionFilter(prev)
        val ok = nm.currentInterruptionFilter == prev
        return ExecResult(if (ok) ResultStatus.Restored else ResultStatus.Failed,
            context.getString(if (ok) R.string.opt_dnd_restored else R.string.opt_dnd_not_confirmed))
    }
}

/** Stops background processes of apps the user confirmed. Android does not confirm per-app, so only the memory change is reported. */
class KillAppsExecutor(private val context: Context) : ActionExecutor {
    override suspend fun apply(action: OptAction, request: ApplyRequest): ExecResult {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val before = avail(am)
        request.stopPackages.forEach { am.killBackgroundProcesses(it) }
        delay(800)
        val deltaMb = (avail(am) - before) / 1_048_576
        return ExecResult(ResultStatus.Applied, context.getString(R.string.opt_kill_done, request.stopPackages.size, deltaMb.toInt()))
    }

    override suspend fun restore(action: OptAction, token: String?) =
        ExecResult(ResultStatus.Restored, context.getString(R.string.opt_kill_nothing))

    private fun avail(am: ActivityManager) = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
}

/** Game mode through the privileged shell (Shizuku). Read back to validate. */
class GameModeExecutor(private val context: Context, private val shell: PrivilegedShell) : ActionExecutor {
    override suspend fun apply(action: OptAction, request: ApplyRequest): ExecResult {
        val pkg = request.targetPackage ?: return ExecResult(ResultStatus.Failed, context.getString(R.string.opt_no_game))
        val level = if (action == OptAction.GameModePerformance) GameModeLevel.Performance else GameModeLevel.Battery
        val previous = shell.getGameMode(pkg)
        val r = shell.setGameMode(pkg, level)
        if (!r.ok) return ExecResult(ResultStatus.Failed, r.output.take(120))
        val now = shell.getGameMode(pkg)
        return if (now != null && now.contains(level.cmdName, ignoreCase = true))
            ExecResult(ResultStatus.Applied, context.getString(R.string.opt_game_mode_set, level.cmdName), "$pkg|${previous ?: ""}")
        else ExecResult(ResultStatus.Failed, context.getString(R.string.opt_not_confirmed))
    }

    override suspend fun restore(action: OptAction, token: String?): ExecResult {
        val pkg = token?.substringBefore('|').orEmpty()
        val prev = token?.substringAfter('|', "").orEmpty()
        if (pkg.isEmpty()) return ExecResult(ResultStatus.Failed, context.getString(R.string.opt_no_previous))
        val level = GameModeLevel.entries.firstOrNull { prev.contains(it.cmdName, ignoreCase = true) } ?: GameModeLevel.Standard
        val r = shell.setGameMode(pkg, level)
        return ExecResult(if (r.ok) ResultStatus.Restored else ResultStatus.Failed, if (r.ok) context.getString(R.string.opt_game_mode_restored) else r.output.take(120))
    }
}

class BatterySaverExecutor(private val context: Context, private val shell: PrivilegedShell) : ActionExecutor {
    override suspend fun apply(action: OptAction, request: ApplyRequest): ExecResult {
        val previous = shell.isBatterySaverOn()
        val r = shell.setBatterySaver(true)
        if (!r.ok) return ExecResult(ResultStatus.Failed, r.output.take(120))
        return if (shell.isBatterySaverOn() == true) ExecResult(ResultStatus.Applied, context.getString(R.string.opt_saver_on), (previous ?: false).toString())
        else ExecResult(ResultStatus.Failed, context.getString(R.string.opt_not_confirmed))
    }

    override suspend fun restore(action: OptAction, token: String?): ExecResult {
        val r = shell.setBatterySaver(token == "true")
        return ExecResult(if (r.ok) ResultStatus.Restored else ResultStatus.Failed, if (r.ok) context.getString(R.string.opt_saver_restored) else r.output.take(120))
    }
}


@Module
@InstallIn(SingletonComponent::class)
object OptimizationModule {
    @Provides @Singleton
    fun capabilities(impl: AndroidCapabilityProvider): CapabilityProvider = impl

    @Provides @Singleton
    fun engine(
        @ApplicationContext context: Context,
        shell: PrivilegedShell,
        capabilities: CapabilityProvider,
        store: EngineStateStore,
        log: OptimizationLogRepository,
    ): OptimizationEngine {
        val game = GameModeExecutor(context, shell)
        val executors = mapOf(
            OptAction.DoNotDisturb to DndExecutor(context),
            OptAction.StopBackgroundApps to KillAppsExecutor(context),
            OptAction.LowLatencyWifi to WifiLockExecutor(context),
            OptAction.ReduceBrightness to BrightnessExecutor(context),
            OptAction.GameModePerformance to game,
            OptAction.GameModeBattery to game,
            OptAction.BatterySaver to BatterySaverExecutor(context, shell),
        )
        return OptimizationEngine(executors, capabilities, StoreStorage(store), RoomLogger(log))
    }
}

/** Low-latency Wi-Fi mode while a game session runs. Affects ping on Wi-Fi, not FPS. */
class WifiLockExecutor(private val context: Context) : ActionExecutor {
    private var lock: android.net.wifi.WifiManager.WifiLock? = null

    override suspend fun apply(action: OptAction, request: ApplyRequest): ExecResult {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        lock?.takeIf { it.isHeld }?.release()
        val l = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "FrameX:low-latency")
        l.setReferenceCounted(false)
        l.acquire()
        lock = l
        return if (l.isHeld) ExecResult(ResultStatus.Applied, context.getString(R.string.opt_wifi_on), "1")
        else ExecResult(ResultStatus.Failed, context.getString(R.string.opt_not_confirmed))
    }

    override suspend fun restore(action: OptAction, token: String?): ExecResult {
        lock?.takeIf { it.isHeld }?.release()
        val released = lock?.isHeld != true
        lock = null
        return ExecResult(if (released) ResultStatus.Restored else ResultStatus.Failed,
            context.getString(if (released) R.string.opt_wifi_off else R.string.opt_not_confirmed))
    }
}

/** Dims the screen to ~30% for less heat, and puts the previous brightness back (unless you changed it yourself meanwhile). */
class BrightnessExecutor(private val context: Context) : ActionExecutor {
    private val resolver get() = context.contentResolver

    override suspend fun apply(action: OptAction, request: ApplyRequest): ExecResult {
        if (!android.provider.Settings.System.canWrite(context)) return ExecResult(ResultStatus.Failed, context.getString(R.string.opt_no_write))
        val prevMode = android.provider.Settings.System.getInt(resolver, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
        val prev = android.provider.Settings.System.getInt(resolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, TARGET)
        if (prev <= TARGET && prevMode == 0) return ExecResult(ResultStatus.Applied, context.getString(R.string.opt_bright_already), "$prevMode|$prev|")
        android.provider.Settings.System.putInt(resolver, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
        android.provider.Settings.System.putInt(resolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, minOf(prev, TARGET))
        val ok = android.provider.Settings.System.getInt(resolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, -1) == minOf(prev, TARGET)
        return if (ok) ExecResult(ResultStatus.Applied, context.getString(R.string.opt_bright_set), "$prevMode|$prev|${minOf(prev, TARGET)}")
        else ExecResult(ResultStatus.Failed, context.getString(R.string.opt_not_confirmed))
    }

    override suspend fun restore(action: OptAction, token: String?): ExecResult {
        val p = token?.split('|') ?: return ExecResult(ResultStatus.Failed, context.getString(R.string.opt_no_previous))
        val mode = p.getOrNull(0)?.toIntOrNull(); val prev = p.getOrNull(1)?.toIntOrNull(); val set = p.getOrNull(2)?.toIntOrNull()
        if (mode == null || prev == null) return ExecResult(ResultStatus.Failed, context.getString(R.string.opt_no_previous))
        if (set == null) return ExecResult(ResultStatus.Restored, context.getString(R.string.opt_bright_kept))
        if (!android.provider.Settings.System.canWrite(context)) return ExecResult(ResultStatus.Failed, context.getString(R.string.opt_no_write))
        val now = android.provider.Settings.System.getInt(resolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, -1)
        if (now != set) return ExecResult(ResultStatus.Restored, context.getString(R.string.opt_bright_kept)) // user changed it; leave it
        android.provider.Settings.System.putInt(resolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, prev)
        android.provider.Settings.System.putInt(resolver, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
        val ok = android.provider.Settings.System.getInt(resolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, -1) == prev
        return ExecResult(if (ok) ResultStatus.Restored else ResultStatus.Failed,
            context.getString(if (ok) R.string.opt_bright_restored else R.string.opt_not_confirmed))
    }

    private companion object { const val TARGET = 77 } // 30% of 255
}

/** Reads what the launch analysis needs. Every value is real or null. */
@Singleton
class FindingsReader @Inject constructor(@ApplicationContext private val context: Context, private val shell: PrivilegedShell) {
    fun read(): Findings {
        val bat = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val tenths = bat?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val temp = if (tenths == null || tenths == Int.MIN_VALUE) null else tenths / 10f
        val charging = (bat?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val ram = if (mi.totalMem > 0) (mi.totalMem - mi.availMem).toFloat() / mi.totalMem else null
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val d = runCatching {
            (context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager).getDisplay(android.view.Display.DEFAULT_DISPLAY)
        }.getOrNull()
        return Findings(temp, charging, ram, pm.isPowerSaveMode, d?.refreshRate, d?.supportedModes?.maxOfOrNull { it.refreshRate }, shell.isAvailable())
    }
}
