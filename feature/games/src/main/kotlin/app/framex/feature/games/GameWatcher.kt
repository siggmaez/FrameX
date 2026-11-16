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
import app.framex.core.common.OptimizationProfile
import app.framex.core.database.GameProfileEntity
import app.framex.core.database.GameSessionEntity
import app.framex.core.database.GamesRepository
import app.framex.core.optimization.ApplyRequest
import app.framex.core.monitoring.FrameCollector
import app.framex.core.monitoring.FrameStats
import app.framex.core.monitoring.SurfaceFlingerLatencyParser
import app.framex.core.monitoring.SurfaceFlingerLayers
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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatcherState @Inject constructor() {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    internal fun set(v: Boolean) { _running.value = v }
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
 * Foreground service started only by the user. Polls the foreground app every 2 s while the screen is on,
 * does nothing while it is off, and applies/restores the profile of games the user selected.
 */
@AndroidEntryPoint
class GameWatcherService : Service() {
    @Inject lateinit var games: GamesRepository
    @Inject lateinit var engine: OptimizationEngine
    @Inject lateinit var state: WatcherState
    @Inject lateinit var shell: PrivilegedShell

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val screenOn = MutableStateFlow(true)
    @Volatile private var profiles: Map<String, GameProfileEntity> = emptyMap()
    private var session: GameSessionEntity? = null
    private var appliedByUs = false
    private var collector = FrameCollector()
    private var layer: String? = null
    private var layerAge = 0

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            screenOn.value = i.action == Intent.ACTION_SCREEN_ON
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.games_channel), NotificationManager.IMPORTANCE_LOW))
        startFg(notification(getString(R.string.games_notif_watching)))
        screenOn.value = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) })
        state.set(true)
        scope.launch { games.closeStaleSessions(System.currentTimeMillis()) }
        scope.launch { games.profiles().collect { list -> profiles = list.associateBy { it.packageName } } }
        scope.launch { screenOn.collect { on -> if (!on) endSession() } }
        scope.launch { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    override fun onDestroy() {
        state.set(false)
        runCatching { unregisterReceiver(screenReceiver) }
        scope.cancel()
        // finish the open session even though this service is going away
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { endSession() }
        super.onDestroy()
    }

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
            val s = session
            val fg = foreground
            if (s == null) {
                profiles[fg]?.let { startSession(it) }
                missing = 0
            } else if (fg == s.packageName) {
                missing = 0
                updateTemp()
                sampleFrames(s.packageName)
            } else if (fg != null && ++missing >= 3) {
                endSession()
                missing = 0
            }
            delay(2000)
        }
    }

    private suspend fun startSession(p: GameProfileEntity) {
        val profile = runCatching { OptimizationProfile.valueOf(p.profile) }.getOrDefault(OptimizationProfile.Gaming)
        var applied: String? = null
        if (p.autoApply && profile != OptimizationProfile.Balanced) {
            // No apps are stopped automatically: that needs the user's confirmation each time.
            val report = engine.apply(profile, ApplyRequest(targetPackage = p.packageName))
            if (report.anyApplied) { applied = profile.name; appliedByUs = true }
        }
        val t = batteryTemp()
        val base = GameSessionEntity(packageName = p.packageName, label = p.label, startMs = System.currentTimeMillis(),
            appliedProfile = applied, startTempC = t, maxTempC = t)
        collector = FrameCollector(); layer = null; layerAge = 0
        session = base.copy(id = games.startSession(base))
        notify(getString(R.string.games_notif_playing, p.label))
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

    private suspend fun updateTemp() {
        val s = session ?: return
        val t = batteryTemp() ?: return
        if (s.maxTempC == null || t > s.maxTempC!!) session = s.copy(maxTempC = t)
    }

    @Synchronized
    private fun takeSession(): GameSessionEntity? = session.also { session = null }

    private suspend fun endSession() {
        val s = takeSession() ?: return
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
