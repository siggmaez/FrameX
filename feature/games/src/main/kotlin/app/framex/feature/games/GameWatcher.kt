package app.framex.feature.games

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import app.framex.core.common.OptimizationProfile
import app.framex.core.database.GameProfileEntity
import app.framex.core.database.GameSessionEntity
import app.framex.core.database.GamesRepository
import app.framex.core.datastore.SettingsRepository
import app.framex.core.monitoring.FrameCollector
import app.framex.core.monitoring.FrameStats
import app.framex.core.monitoring.SurfaceFlingerLatencyParser
import app.framex.core.monitoring.SurfaceFlingerLayers
import app.framex.core.optimization.ApplyRequest
import app.framex.core.optimization.OptimizationEngine
import app.framex.core.optimization.PrivilegedShell
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A launch the user started with the Play button: the profile to apply once the game shows up. */
data class ArmedLaunch(val profile: OptimizationProfile, val stopPackages: List<String>, val armedAtMs: Long = System.currentTimeMillis())

@Singleton
class WatcherState @Inject constructor() {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    internal fun set(v: Boolean) { _running.value = v }

    private val armed = ConcurrentHashMap<String, ArmedLaunch>()
    fun arm(pkg: String, launch: ArmedLaunch) { armed[pkg] = launch }
    /** Returns and removes the armed launch if it is not older than [MAX_AGE_MS]. */
    internal fun take(pkg: String): ArmedLaunch? = armed.remove(pkg)?.takeIf { System.currentTimeMillis() - it.armedAtMs < MAX_AGE_MS }
    internal fun purge() { val now = System.currentTimeMillis(); armed.entries.removeIf { now - it.value.armedAtMs >= MAX_AGE_MS } }

    private companion object { const val MAX_AGE_MS = 60_000L }
}

@Singleton
class WatcherController @Inject constructor(@ApplicationContext private val context: Context, val state: WatcherState) {
    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }
    fun start() { context.startForegroundService(Intent(context, GameWatcherService::class.java)) }
    fun stop() { context.stopService(Intent(context, GameWatcherService::class.java)) }
}

/**
 * Foreground service started only by the user (switch or Play button). Checks the foreground app while the
 * screen is on, does nothing while it is off, applies/restores the profile of the user's games, records the
 * session, and shows the FPS overlay if the user turned it on and allowed "draw over other apps".
 */
@AndroidEntryPoint
class GameWatcherService : Service() {
    @Inject lateinit var games: GamesRepository
    @Inject lateinit var engine: OptimizationEngine
    @Inject lateinit var state: WatcherState
    @Inject lateinit var shell: PrivilegedShell
    @Inject lateinit var settings: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val screenOn = MutableStateFlow(true)
    @Volatile private var profiles: Map<String, GameProfileEntity> = emptyMap()
    @Volatile private var overlayEnabled = false
    private var session: GameSessionEntity? = null
    private var appliedByUs = false
    private var collector = FrameCollector()
    private var layer: String? = null
    private var layerAge = 0
    private var lastFrameCount = 0
    private var staleTicks = 0
    private lateinit var overlay: FpsOverlay

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { screenOn.value = i.action == Intent.ACTION_SCREEN_ON }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        overlay = FpsOverlay(this)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.games_channel), NotificationManager.IMPORTANCE_LOW))
        startFg(notification(getString(R.string.games_notif_watching)))
        screenOn.value = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) })
        state.set(true)
        scope.launch { games.closeStaleSessions(System.currentTimeMillis()) }
        scope.launch { games.profiles().collect { list -> profiles = list.associateBy { it.packageName } } }
        scope.launch {
            settings.settings.collect { s ->
                overlayEnabled = s.fpsOverlayEnabled
                if (session != null) { if (canOverlay()) overlay.show() else overlay.hide() }
                if (!overlayEnabled) overlay.hide()
            }
        }
        scope.launch { screenOn.collect { on -> if (!on) endSession() } }
        scope.launch { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    override fun onDestroy() {
        state.set(false)
        runCatching { unregisterReceiver(screenReceiver) }
        overlay.hide()
        scope.cancel()
        // finish the open session even though this service is going away
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { endSession() }
        super.onDestroy()
    }

    private fun canOverlay() = overlayEnabled && Settings.canDrawOverlays(this)

    private suspend fun loop() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        var lastQuery = System.currentTimeMillis() - 60_000
        var foreground: String? = null
        var missing = 0
        while (scope.isActive) {
            screenOn.first { it }
            val now = System.currentTimeMillis()
            runCatching {
                val events = usm.queryEvents(lastQuery, now)
                val e = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(e)
                    if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) foreground = e.packageName
                }
            }
            lastQuery = now
            state.purge()
            val s = session
            val fg = foreground
            if (s == null) {
                profiles[fg]?.let { startSession(it) }
                missing = 0
            } else if (fg == s.packageName) {
                missing = 0
                updateTemp()
                sampleFrames(s.packageName)
                refreshOverlay()
            } else if (fg != null && ++missing >= 3) {
                endSession()
                missing = 0
            }
            // 1 s while measuring frames (SurfaceFlinger keeps ~127 frames, so a slower poll would miss some at high FPS)
            delay(if (session != null && shell.isAvailable()) 1000 else 2000)
        }
    }

    private suspend fun startSession(p: GameProfileEntity) {
        val armed = state.take(p.packageName)
        val profile = armed?.profile
            ?: if (p.autoApply) runCatching { OptimizationProfile.valueOf(p.profile) }.getOrDefault(OptimizationProfile.Gaming) else null
        var applied: String? = null
        if (profile != null && profile != OptimizationProfile.Balanced) {
            // Apps are stopped only if the user approved them (Play button confirmation or a remembered list).
            val custom = settings.settings.first().customActions
            val report = engine.apply(profile, ApplyRequest(targetPackage = p.packageName, stopPackages = armed?.stopPackages.orEmpty(), customActions = custom))
            if (report.anyApplied) { applied = profile.name; appliedByUs = true }
        }
        val t = batteryTemp()
        val base = GameSessionEntity(packageName = p.packageName, label = p.label, startMs = System.currentTimeMillis(),
            appliedProfile = applied, startTempC = t, maxTempC = t)
        collector = FrameCollector(); layer = null; layerAge = 0; lastFrameCount = 0; staleTicks = 0
        session = base.copy(id = games.startSession(base))
        notify(getString(R.string.games_notif_playing, p.label))
        if (canOverlay()) { overlay.show(); refreshOverlay() }
    }

    /** Real frame timestamps from SurfaceFlinger; only possible while Shizuku is connected. */
    private suspend fun sampleFrames(pkg: String) {
        if (!shell.isAvailable()) return
        if (layer == null || ++layerAge > 30) {
            layer = shell.surfaceFlingerLayers()?.let { SurfaceFlingerLayers.pick(it, pkg) }
            layerAge = 0
        }
        val l = layer ?: return
        val text = shell.surfaceFlingerLatency(l)
        if (text == null) { layer = null; return }
        collector.add(SurfaceFlingerLatencyParser.parse(text))
    }

    private fun refreshOverlay() {
        if (!canOverlay()) return
        val temp = batteryTemp()?.let { String.format(java.util.Locale.US, "%.1f °C", it) } ?: "—"
        val fpsText = if (!shell.isAvailable()) getString(R.string.overlay_fps_na) else {
            val count = collector.frameTimesMs.size
            staleTicks = if (count == lastFrameCount) staleTicks + 1 else 0
            lastFrameCount = count
            val fps = if (staleTicks >= 3) null else collector.currentFps() // no new frames for 3 s: do not show an old number
            fps?.let { getString(R.string.overlay_fps, it.toInt()) } ?: getString(R.string.overlay_fps_wait)
        }
        overlay.update("$fpsText\n$temp")
    }

    private suspend fun updateTemp() {
        val s = session ?: return
        val t = batteryTemp() ?: return
        if (s.maxTempC == null || t > s.maxTempC!!) session = s.copy(maxTempC = t)
    }

    @Synchronized
    private fun takeSession(): GameSessionEntity? = session.also { session = null }

    private suspend fun endSession() {
        val s = takeSession() ?: return
        overlay.hide()
        if (appliedByUs) { engine.restore(); appliedByUs = false }
        val t = batteryTemp()
        val fps = FrameStats.from(collector.frameTimesMs)
        games.updateSession(s.copy(endMs = System.currentTimeMillis(), endTempC = t, maxTempC = maxOfNullable(s.maxTempC, t),
            avgFps = fps?.avgFps, minFps = fps?.minFps, lowFps = fps?.onePercentLowFps))
        notify(getString(R.string.games_notif_watching))
    }

    private fun maxOfNullable(a: Float?, b: Float?): Float? = if (a == null) b else if (b == null) a else maxOf(a, b)

    private fun batteryTemp(): Float? {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (t == Int.MIN_VALUE) null else t / 10f
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_framex).setContentTitle("FrameX")
            .setContentText(text).setOngoing(true).build()

    private fun notify(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, notification(text))
    }

    private fun startFg(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID, n)
    }

    private companion object {
        const val CHANNEL = "game_watcher"
        const val ID = 1001
    }
}
