package app.framex.feature.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.framex.core.common.Metric
import app.framex.core.database.MetricHistoryRepository
import app.framex.core.database.MetricType
import app.framex.core.datastore.SettingsRepository
import app.framex.core.monitoring.DetailedMetricsSource
import app.framex.core.monitoring.DetailedSnapshot
import app.framex.core.monitoring.FrameStats
import app.framex.core.optimization.PrivilegedShell
import kotlinx.coroutines.delay
import app.framex.core.database.GamesRepository
import app.framex.core.monitoring.NetworkDiagnostics
import app.framex.core.common.ConnectionKind
import app.framex.core.common.NetworkMode
import app.framex.core.monitoring.NetResult
import app.framex.core.monitoring.PingTarget
import app.framex.core.monitoring.SystemMetricsSource
import app.framex.core.monitoring.SystemSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LiveState(val snapshot: SystemSnapshot? = null, val detailed: DetailedSnapshot? = null)

data class NetworkUiState(
    val running: Boolean = false,
    val target: PingTarget = PingTarget.Cloudflare,
    /** Connections the user ticked for the next run (limited to what the network mode allows). */
    val selected: Set<ConnectionKind> = setOf(ConnectionKind.Wifi),
    val results: Map<ConnectionKind, NetResult> = emptyMap(),
)

private const val HISTORY_POINTS = 120

@HiltViewModel
class MonitorViewModel @Inject constructor(
    settings: SettingsRepository,
    source: SystemMetricsSource,
    detailedSource: DetailedMetricsSource,
    private val history: MetricHistoryRepository,
    private val network: NetworkDiagnostics,
    private val shell: PrivilegedShell,
    games: GamesRepository,
) : ViewModel() {

    /** Polls and records only while the Monitor screen is collected. */
    val live: StateFlow<LiveState> = source.snapshots(settings.settings.map { it.monitoringIntervalMs })
        .map { LiveState(it, detailedSource.read()) }
        .onEach { s -> s.snapshot?.let { record(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveState())

    private fun series(type: MetricType): StateFlow<List<Float>> =
        history.history(type, HISTORY_POINTS)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val ramHistory = series(MetricType.RamUsedPercent)
    val cpuHistory = series(MetricType.CpuLoadPercent)
    val tempHistory = series(MetricType.BatteryTempC)
    val pingHistory = series(MetricType.PingMs)
    val pingMobileHistory = series(MetricType.PingMobileMs)

    /** Where FrameX may use the network (Settings). */
    val networkMode: StateFlow<NetworkMode> = settings.settings.map { it.networkMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetworkMode.WifiAndMobile)

    private val _net = MutableStateFlow(NetworkUiState())
    val net: StateFlow<NetworkUiState> = _net.asStateFlow()

    private suspend fun record(s: SystemSnapshot) {
        (s.ram as? Metric.Available)?.let { history.record(MetricType.RamUsedPercent, it.value.usedFraction * 100f) }
        (s.cpuLoad as? Metric.Available)?.let { history.record(MetricType.CpuLoadPercent, it.value * 100f) }
        (s.battery as? Metric.Available)?.let { history.record(MetricType.BatteryTempC, it.value.temperatureC) }
    }


    /** Latest recorded game session, to show its measured FPS (or why there is none). */
    val lastSession = games.sessions(1).map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val shizukuAvailable: Boolean get() = shell.isAvailable()

    private val _frameTest = MutableStateFlow(FrameTestUi())
    val frameTest: StateFlow<FrameTestUi> = _frameTest.asStateFlow()
    private val frames = ArrayList<Float>(2048)

    /** Called by the test canvas for every frame it receives. */
    fun onFrame(ms: Float) { if (_frameTest.value.running) frames += ms }

    fun startFrameTest(seconds: Int = 10) {
        if (_frameTest.value.running) return
        frames.clear()
        _frameTest.value = FrameTestUi(running = true, secondsLeft = seconds)
        viewModelScope.launch {
            val steps = seconds * 2
            repeat(steps) { i ->
                delay(500)
                var acc = 0f; var n = 0
                for (k in frames.indices.reversed()) { acc += frames[k]; n++; if (acc >= 1000f) break }
                _frameTest.update { it.copy(secondsLeft = seconds - (i + 1) / 2, liveFps = if (acc >= 500f) n * 1000f / acc else null) }
            }
            val all = frames.toList()
            val step = (all.size / 300).coerceAtLeast(1)
            _frameTest.value = FrameTestUi(false, 0, null, FrameStats.from(all), all.filterIndexed { i, _ -> i % step == 0 })
        }
    }

    fun setTarget(t: PingTarget) = _net.update { it.copy(target = t) }

    fun toggleKind(k: ConnectionKind) = _net.update { s ->
        val next = if (k in s.selected) s.selected - k else s.selected + k
        s.copy(selected = if (next.isEmpty()) s.selected else next) // always keep one ticked
    }

    /** Tests every ticked connection that the network mode allows, one after another. */
    fun runDiagnostics() {
        if (_net.value.running) return
        viewModelScope.launch {
            val mode = networkMode.value
            val kinds = mode.allowed.filter { it in _net.value.selected }.ifEmpty { mode.allowed.take(1) }
            if (kinds.isEmpty()) return@launch // Local mode: no connection is ever opened
            _net.update { it.copy(running = true, results = emptyMap()) }
            try {
                for (k in kinds) {
                    val r = network.diagnose(k, _net.value.target, mode) { ping ->
                        ping.avgMs?.let { ms ->
                            viewModelScope.launch { history.record(if (k == ConnectionKind.Wifi) MetricType.PingMs else MetricType.PingMobileMs, ms) }
                        }
                    }
                    _net.update { it.copy(results = it.results + (k to r)) }
                }
            } finally {
                _net.update { it.copy(running = false) }
            }
        }
    }
}

data class FrameTestUi(
    val running: Boolean = false,
    val secondsLeft: Int = 0,
    val liveFps: Float? = null,
    val stats: FrameStats? = null,
    /** Frame times (ms) of the last run, thinned for the graph. */
    val graph: List<Float> = emptyList(),
)
