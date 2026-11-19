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
import app.framex.core.common.OptAction
import app.framex.core.common.OptimizationProfile
import app.framex.core.common.OverlayScope
import app.framex.core.designsystem.overlay.OverlayColors
import app.framex.core.designsystem.overlay.OverlayContent
import app.framex.core.database.GameProfileEntity
import app.framex.core.database.GameSessionEntity
import app.framex.core.database.GamesRepository
import app.framex.core.datastore.SettingsRepository
import app.framex.core.monitoring.FrameCollector
import app.framex.core.monitoring.FrameStats
import app.framex.core.monitoring.SurfaceFlingerLatencyParser
import app.framex.core.monitoring.TimeStatsParser
import app.framex.core.monitoring.SurfaceFlingerLayers
import app.framex.core.optimization.ApplyRequest
import app.framex.core.optimization.BoostLevel
import app.framex.core.optimization.GameBoosts
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
/** [preApplied]: the whole boost (including the lower screen resolution) was already applied before the game opened; the watcher only restores it afterwards. */
data class ArmedLaunch(val profile: OptimizationProfile, val stopPackages: List<String>, val armedAtMs: Long = System.currentTimeMillis(), val preApplied: Boolean = false)

@Singleton
class WatcherState @Inject constructor() {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    internal fun set(v: Boolean) { _running.value = v }

    /** What FrameX currently sees in front, for the status line in Settings (diagnostics). */
    data class Seen(val pkg: String?, val tracked: Boolean)
    private val _seen = MutableStateFlow(Seen(null, false))
    val seen: StateFlow<Seen> = _seen.asStateFlow()
    private val _recent = MutableStateFlow<List<String>>(emptyList())
    /** The last few things FrameX noticed, newest first, so a missing overlay can be explained. */
    val recent: StateFlow<List<String>> = _recent.asStateFlow()
    private val _fpsDebug = MutableStateFlow("")
    /** Why FPS is or is not shown: what the measuring step last saw. */
    val fpsDebug: StateFlow<String> = _fpsDebug.asStateFlow()
    internal fun setFpsDebug(s: String) { _fpsDebug.value = s }
    internal fun log(line: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        _recent.value = (listOf("$t  $line") + _recent.value).take(14)
    }
    internal fun setSeen(pkg: String?, tracked: Boolean) { if (_seen.value != Seen(pkg, tracked)) _seen.value = Seen(pkg, tracked) }

    private val armed = ConcurrentHashMap<String, ArmedLaunch>()
    fun arm(pkg: String, launch: ArmedLaunch) { armed[pkg] = launch }
    /** Returns and removes the armed launch if it is not older than [MAX_AGE_MS]. */
    internal fun take(pkg: String): ArmedLaunch? = armed.remove(pkg)?.takeIf { System.currentTimeMillis() - it.armedAtMs < MAX_AGE_MS }
    /** Drops launches that never happened. True if one of them had already changed the system (Ultra), so it must be undone. */
    internal fun purge(): Boolean {
        val now = System.currentTimeMillis(); var hard = false
        armed.entries.removeIf { val old = now - it.value.armedAtMs >= MAX_AGE_MS; if (old && it.value.preApplied) hard = true; old }
        return hard
    }

    private companion object { const val MAX_AGE_MS = 60_000L }
}

@Singleton
class WatcherController @Inject constructor(
    @ApplicationContext private val context: Context,
    val state: WatcherState,
    private val engine: app.framex.core.optimization.OptimizationEngine,
    private val settings: SettingsRepository,
) {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The overlay needs the detection service. If the overlay is switched on and everything it needs is granted,
     * (re)start the service. Called from the foreground (app start, switching the overlay on), where Android allows it.
     */
    fun startIfWantedAsync() {
        // if FrameX stopped while the screen resolution was lowered and no game session is running, put it back
        io.launch { if (!state.running.value) runCatching { engine.restoreIfHard() } }
        io.launch {
            val s = settings.settings.first()
            if (s.fpsOverlayEnabled && android.provider.Settings.canDrawOverlays(context) && hasUsageAccess() && !state.running.value) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { start() } }
            }
        }
    }

    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }
    fun start() { context.startForegroundService(Intent(context, GameWatcherService::class.java)) }

    /** Starts the service if needed and asks it to show a sample overlay for 30 s. */
    fun testOverlay() { context.startForegroundService(Intent(context, GameWatcherService::class.java).setAction(GameWatcherService.ACTION_TEST)) }
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
    @Inject lateinit var capture: CaptureFpsController
    @Inject lateinit var adb: app.framex.core.adb.AdbController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val screenOn = MutableStateFlow(true)
    @Volatile private var profiles: Map<String, GameProfileEntity> = emptyMap()
    @Volatile private var overlayEnabled = false
    @Volatile private var overlayScope = OverlayScope.Games
    @Volatile private var styleNow = app.framex.core.common.OverlayStyle.Stealth
    private var passivePkg: String? = null
    private enum class SfMode { TimeStatsLight, TimeStats, Latency }
    private var sfMode = SfMode.TimeStatsLight
    private var lastSampleStart = 0L
    private var tsEnabled = false
    private var modeEmpty = 0
    private var tsSamples = 0
    private var lastTsFps: Float? = null
    private var layerMissing = false
    private var listedCount: Int? = null
    private var lastSampleAt = 0L
    private var lastShellGoodAt = 0L
    private var targetStartedAt = 0L
    private val gameCache = HashMap<String, Boolean>()
    private val homePackages: Set<String> by lazy {
        val home = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).map { it.activityInfo.packageName }
        (home + "com.android.systemui").toSet()
    }
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
        scope.launch { adb.ensureConnected() }
        scope.launch { games.profiles().collect { list -> profiles = list.associateBy { it.packageName } } }
        scope.launch {
            settings.settings.collect { s ->
                overlayEnabled = s.fpsOverlayEnabled
                overlayScope = s.overlayScope
                styleNow = s.overlayStyle
                overlay.setStyle(s.overlayStyle)
                if (session != null) { if (canOverlay()) overlay.show() else overlay.hide() }
                if (!overlayEnabled) overlay.hide()
            }
        }
        scope.launch { screenOn.collect { on -> state.log(if (on) "screen on" else "screen off"); if (!on) { endSession(); passivePkg = null; overlay.hide() } } }
        scope.launch { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TEST) showTestOverlay()
        return START_NOT_STICKY
    }

    /** Shows the overlay with sample numbers for 30 s, over whatever is open, to check that Android lets it appear there. */
    private fun showTestOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        overlay.setStyle(styleNow)
        overlay.show()
        overlay.update(OverlayContent("TEST", getString(R.string.overlay_unit_test), OverlayColors.GOOD, "36.6°C", OverlayColors.TEXT))
        scope.launch { delay(30_000); if (session == null && passivePkg == null) overlay.hide() }
    }

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
        // look far back once, so a game that was already open when the service started is still found
        var lastQuery = System.currentTimeMillis() - 3 * 3600_000L
        val tracker = ForegroundTracker()
        var foreground: String? = null
        var lastLogged: String? = "?"
        var missing = 0
        while (scope.isActive) {
            screenOn.first { it }
            try {
            val now = System.currentTimeMillis()
            runCatching {
                // windows overlap by 3 s: usage events can be written a moment late and must not be lost
                val events = usm.queryEvents(lastQuery - 3_000, now)
                val e = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(e)
                    val type = when (e.eventType) {
                        UsageEvents.Event.ACTIVITY_RESUMED -> FgEvent.Resumed
                        UsageEvents.Event.ACTIVITY_PAUSED -> FgEvent.Paused
                        UsageEvents.Event.ACTIVITY_STOPPED -> FgEvent.Stopped
                        else -> null
                    }
                    if (type != null) tracker.onEvent(type, e.packageName, e.className)
                }
            }
            lastQuery = now
            foreground = tracker.foreground
            if (state.purge() && session == null) engine.restore() // Ultra was applied but the game never opened: put everything back
            // capture was started for a launch that never happened: do not keep recording the screen
            if (session == null && capture.running.value && now - capture.startedAtMs > 90_000) capture.stop()
            val s = session
            val fg = foreground
            state.setSeen(fg, fg != null && profiles.containsKey(fg))
            if (fg != lastLogged) { lastLogged = fg; state.log("front: ${fg ?: "—"} (${if (fg != null && profiles.containsKey(fg)) "in My games" else "not in My games"})") }
            if (!shell.isAvailable()) scope.launch { shell.ensureReady() } // the shell link may have dropped since the service started (debounced inside)
            if (s == null) {
                val tracked = profiles[fg]
                if (tracked != null) { passivePkg = null; startSession(tracked) } else passive(fg)
                missing = 0
            } else if (fg == s.packageName) {
                missing = 0
                updateTemp()
                refreshOverlay()
                sampleAsync(s.packageName)
            } else if (fg != null && ++missing >= 3) {
                endSession()
                missing = 0
            }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                state.log("error: ${e.javaClass.simpleName}: ${e.message}") // a failure in one round must not stop the service
            }
            // 1 s while measuring frames (SurfaceFlinger keeps ~127 frames, so a slower poll would miss some at high FPS)
            val measuring = session != null || passivePkg != null
            delay(if (measuring && (shell.isAvailable() || capture.running.value)) 1000 else 2000)
        }
    }

    /** Overlay for apps that are not in "My games" (depending on the overlay scope). No profile, no session record. */
    private suspend fun passive(fg: String?) {
        val allowed = fg != null && fg != packageName && fg !in homePackages && canOverlay() && when (overlayScope) {
            OverlayScope.Tracked -> false
            OverlayScope.Games -> isGame(fg)
            OverlayScope.Apps -> true
        }
        if (!allowed || fg == null) {
            if (passivePkg != null) { passivePkg = null; overlay.hide(); stopTimeStats() }
            return
        }
        if (passivePkg != fg) {
            passivePkg = fg
            collector = FrameCollector(); layer = null; layerAge = 0; lastFrameCount = 0; staleTicks = 0; tsSamples = 0; lastTsFps = null; modeEmpty = 0; lastSampleAt = android.os.SystemClock.elapsedRealtime(); lastShellGoodAt = 0L; targetStartedAt = lastSampleAt
            capture.reset()
        }
        refreshOverlay()
        sampleAsync(fg)
    }

    private fun isGame(pkg: String): Boolean = gameCache.getOrPut(pkg) {
        runCatching {
            @Suppress("DEPRECATION")
            val i = packageManager.getApplicationInfo(pkg, 0)
            i.category == android.content.pm.ApplicationInfo.CATEGORY_GAME || i.flags and android.content.pm.ApplicationInfo.FLAG_IS_GAME != 0
        }.getOrDefault(false)
    }

    private suspend fun startSession(p: GameProfileEntity) {
        if (canOverlay()) overlay.show() // visible at once; the profile below may take a few seconds
        val armed = state.take(p.packageName)
        val profile = armed?.profile
            ?: if (p.autoApply) runCatching { OptimizationProfile.valueOf(p.profile) }.getOrDefault(OptimizationProfile.Gaming) else null
        // this game's own boost level; Off = nothing extra
        val level = runCatching { BoostLevel.valueOf(p.boostLevel) }.getOrDefault(BoostLevel.Off)
        val boost = GameBoosts.plan(p.packageName, level, supportedRefreshRates(this)) // null for games without a tuned strategy
        val pingExtras = if (p.pingBoost) setOf(OptAction.LowLatencyWifi, OptAction.WifiLowLatencyMode, OptAction.BackgroundDataLimit) else emptySet()
        var applied: String? = null
        val preApplied = armed?.preApplied == true // Ultra: already applied before the game opened
        if (preApplied) {
            applied = (profile ?: OptimizationProfile.Performance).name
            appliedByUs = true // the watcher restores it when the session ends
        } else if ((profile != null && profile != OptimizationProfile.Balanced) || ((boost != null || pingExtras.isNotEmpty()) && (profile != null || armed != null))) {
            // Apps are stopped only if the user approved them (Play button confirmation, a remembered list or the automatic boost).
            val custom = settings.settings.first().customActions
            val report = engine.apply(
                profile ?: OptimizationProfile.Balanced,
                ApplyRequest(
                    targetPackage = p.packageName, stopPackages = armed?.stopPackages.orEmpty(), customActions = custom,
                    extraActions = (boost?.extraActions.orEmpty() - OptAction.ScreenResolution) + pingExtras, // never change the screen size under a running game
                    renderScale = boost?.renderScale, refreshHz = boost?.refreshHz,
                ),
            )
            if (report.anyApplied) { applied = (profile ?: OptimizationProfile.Balanced).name; appliedByUs = true }
        }
        val t = batteryTemp()
        val base = GameSessionEntity(packageName = p.packageName, label = p.label, startMs = System.currentTimeMillis(),
            appliedProfile = applied, startTempC = t, maxTempC = t, boostLevel = if (applied != null && boost != null) level.name else BoostLevel.Off.name)
        collector = FrameCollector(); layer = null; layerAge = 0; lastFrameCount = 0; staleTicks = 0; tsSamples = 0; lastTsFps = null; modeEmpty = 0; lastSampleAt = android.os.SystemClock.elapsedRealtime(); lastShellGoodAt = 0L; targetStartedAt = lastSampleAt
        capture.reset() // frames from before the game opened (FrameX, launcher) must not count
        session = base.copy(id = games.startSession(base))
        notify(getString(R.string.games_notif_playing, p.label))
        state.log("session started: ${p.label}${if (applied != null) ", boost applied" else ""}")
        if (canOverlay()) { overlay.show(); refreshOverlay() }
    }

    private val debugByMode = LinkedHashMap<String, String>()

    /** Keeps the last result of every measuring method, so the diagnostics show all of them, not only the latest. */
    private fun dbg(text: String) {
        debugByMode[sfMode.name] = text
        state.setFpsDebug(debugByMode.entries.joinToString("\n— — —\n") { "[${it.key}] ${it.value}" })
    }

    private var sampling = false

    /**
     * Measures in its own task, with a hard 6 s limit, so a slow or stuck SurfaceFlinger answer can never hold up the
     * overlay or the foreground detection. Only one measurement runs at a time.
     */
    private fun sampleAsync(pkg: String) {
        if (sampling) return
        // SurfaceFlinger dumps are heavy while a game runs: every 3 s is enough (the overlay still updates every second)
        val gap = if (sfMode == SfMode.Latency) 1_000L else 3_000L
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSampleStart < gap) return
        lastSampleStart = now
        sampling = true
        scope.launch {
            try {
                if (kotlinx.coroutines.withTimeoutOrNull(15_000) { sampleFrames(pkg); true } == null) {
                    dbg("method=$sfMode: no answer from SurfaceFlinger within 15 s")
                    noteEmpty() // a timeout is a failed round too, so the next method gets its turn
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                dbg("measuring failed: ${e.javaClass.simpleName}: ${e.message}")
                noteEmpty()
            } finally {
                sampling = false
            }
        }
    }

    /**
     * Real frame data from SurfaceFlinger; needs shell access. TimeStats first (works on newer Android, including 15),
     * the older --latency as a fallback. If a method returns nothing for [SWITCH_AFTER] samples, the other one is tried,
     * and the one that works is remembered while the service runs.
     */
    private suspend fun sampleFrames(pkg: String) {
        if (!shell.isAvailable()) { dbg("no shell access (Shizuku / wireless debugging not connected)"); return }
        var got = false
        val t0 = android.os.SystemClock.elapsedRealtime()
        if (sfMode != SfMode.Latency) {
            if (!tsEnabled) { shell.timeStatsEnable(); tsEnabled = true }
            val r = shell.timeStatsDumpClear(light = sfMode == SfMode.TimeStatsLight)
            val sample = TimeStatsParser.parse(r.output, pkg)
            val now = android.os.SystemClock.elapsedRealtime()
            val elapsedS = (now - lastSampleAt) / 1000f
            lastSampleAt = now
            if (sample != null && sample.totalFrames > 0) {
                got = true
                lastShellGoodAt = android.os.SystemClock.elapsedRealtime()
                // the dump's own average if present, otherwise frames shown since the previous dump / seconds since then
                val fps = sample.averageFps ?: if (elapsedS in 0.3f..6f) sample.totalFrames / elapsedS else null
                fps?.let { lastTsFps = it }
                collector.addFrameTimes(sample.frameTimesMs)
                tsSamples++
            }
            val names = TimeStatsParser.layerNames(r.output).take(4)
            dbg(buildString {
                append("method=$sfMode shell-ok=${r.ok} dump=${r.output.length} chars took=${android.os.SystemClock.elapsedRealtime() - t0} ms\n")
                append("layers in dump=${TimeStatsParser.layerNames(r.output).size} first=$names\n")
                append("game=$pkg match=${sample?.layer ?: "none"} frames=${sample?.totalFrames} avgFps=${sample?.averageFps} shown=${lastTsFps}")
                if (r.output.length < 300) append("\nreply: ${r.output.replace('\n', ' ')}")
            })
        } else {
            if (layer == null || ++layerAge > 30) {
                val listed = shell.surfaceFlingerLayers()
                listedCount = listed?.size
                layer = listed?.let { SurfaceFlingerLayers.pick(it, pkg) }
                layerAge = 0
            }
            layerMissing = layer == null
            var parsed = -1
            layer?.let { l ->
                val text = shell.surfaceFlingerLatency(l)
                if (text == null) layer = null else {
                    val before = collector.frameTimesMs.size
                    parsed = SurfaceFlingerLatencyParser.parse(text).also { collector.add(it) }.size
                    got = collector.frameTimesMs.size > before
                    if (got) lastShellGoodAt = android.os.SystemClock.elapsedRealtime()
                }
            }
            dbg("method=latency layers listed=${listedCount ?: "listing failed"} layer=${layer ?: "not found"} timestamps parsed=$parsed frames kept=${collector.frameTimesMs.size} took=${android.os.SystemClock.elapsedRealtime() - t0} ms")
        }
        if (got) modeEmpty = 0 else noteEmpty()
    }

    /** A round that produced nothing, or timed out: after [SWITCH_AFTER] of them, try the next measuring method. */
    private fun noteEmpty() {
        if (++modeEmpty < SWITCH_AFTER) return
        modeEmpty = 0
        sfMode = when (sfMode) {
            SfMode.TimeStatsLight -> SfMode.TimeStats
            SfMode.TimeStats -> SfMode.Latency
            SfMode.Latency -> SfMode.TimeStatsLight
        }
        layer = null; layerMissing = false
        state.log("measuring method now: $sfMode")
    }

    private fun stopTimeStats() {
        if (!tsEnabled) return
        tsEnabled = false
        CoroutineScope(Dispatchers.IO).launch { runCatching { shell.timeStatsDisable() } } // less work for SurfaceFlinger when nobody is measuring
    }

    /** Measured through SurfaceFlinger if Shizuku is connected, else estimated from screen capture, else none. */
    private enum class Source { Measured, Estimated, None }

    /**
     * Measured (SurfaceFlinger through the shell) while the shell keeps answering; if it stops answering for a while
     * (a running game can starve background commands) the screen-capture ESTIMATE takes over when it is running.
     */
    private fun source(): Source {
        val now = android.os.SystemClock.elapsedRealtime()
        val shellOk = shell.isAvailable()
        val fresh = shellOk && (if (lastShellGoodAt > 0) now - lastShellGoodAt < 9_000 else now - targetStartedAt < 10_000)
        return when {
            fresh -> Source.Measured
            capture.running.value -> Source.Estimated
            shellOk -> Source.Measured
            else -> Source.None
        }
    }

    private fun activeTimes(src: Source): List<Float> = when (src) {
        Source.Measured -> collector.frameTimesMs
        Source.Estimated -> capture.frameTimes()
        Source.None -> emptyList()
    }

    private fun refreshOverlay() {
        if (!canOverlay()) return
        overlay.show() // idempotent: if the window was never created or the system dropped it, it comes back on the next tick
        val tempC = batteryTemp()
        val temp = tempC?.let { String.format(java.util.Locale.US, "%.1f°C", it) } ?: "—"
        val src = source()
        var value = "—"
        var unit = getString(R.string.overlay_unit_noshell) // no shell access and no capture: say why, not just "n/a"
        var fpsInt: Int? = null
        if (src != Source.None) {
            val count = if (src == Source.Measured && sfMode != SfMode.Latency) tsSamples else activeTimes(src).size
            staleTicks = if (count == lastFrameCount) staleTicks + 1 else 0
            lastFrameCount = count
            // no new frames for 3 s: do not show an old number
            val fps = when {
                staleTicks >= 9 -> null
                src == Source.Measured && sfMode != SfMode.Latency -> lastTsFps
                src == Source.Measured -> collector.currentFps()
                else -> capture.currentFps()
            }
            if (fps == null) unit = getString(if (src == Source.Measured && sfMode == SfMode.Latency && layerMissing) R.string.overlay_unit_nolayer else R.string.overlay_unit_wait)
            else {
                fpsInt = fps.toInt()
                value = if (src == Source.Estimated) "~$fpsInt" else "$fpsInt"
                unit = getString(if (src == Source.Estimated) R.string.overlay_unit_est else R.string.overlay_unit)
            }
        }
        overlay.update(OverlayContent(value, unit, OverlayColors.forFps(fpsInt), temp, OverlayColors.forTemp(tempC)))
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
        stopTimeStats()
        state.log("session ended: ${s.label}")
        overlay.hide()
        if (appliedByUs) { engine.restore(); appliedByUs = false }
        val t = batteryTemp()
        val src = source()
        val fps = FrameStats.from(activeTimes(src))
        if (capture.running.value) capture.stop() // one consent = one session
        games.updateSession(s.copy(endMs = System.currentTimeMillis(), endTempC = t, maxTempC = maxOfNullable(s.maxTempC, t),
            avgFps = fps?.avgFps, minFps = fps?.minFps, lowFps = fps?.onePercentLowFps,
            fpsSource = if (fps == null) null else if (src == Source.Measured) "SurfaceFlinger" else "ScreenCapture"))
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

    companion object {
        const val ACTION_TEST = "app.framex.feature.games.TEST_OVERLAY"
        const val CHANNEL = "game_watcher"
        const val SWITCH_AFTER = 3

        const val ID = 1001
    }
}
