package app.framex.feature.benchmark

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.framex.core.benchmark.BenchCodec
import app.framex.core.benchmark.BenchOutcome
import app.framex.core.benchmark.BenchType
import app.framex.core.benchmark.Benchmarks
import app.framex.core.database.BenchmarkRepository
import app.framex.core.database.BenchmarkResultEntity
import app.framex.core.datastore.EngineStateStore
import app.framex.core.monitoring.FrameStats
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import javax.inject.Inject

data class BenchUi(
    val running: BenchType? = null,
    val progress: Float = 0f,
    val results: List<BenchmarkResultEntity> = emptyList(),
    val selected: List<Long> = emptyList(),
    val hotTempC: Float? = null,
    val error: String? = null,
)

private data class Local(val running: BenchType? = null, val progress: Float = 0f, val selected: List<Long> = emptyList(), val hot: Float? = null, val error: String? = null)

private const val HOT_LIMIT_C = 45f
private const val GRAPHICS_SECONDS = 8

@HiltViewModel
class BenchmarkViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: BenchmarkRepository,
    private val engineState: EngineStateStore,
) : ViewModel() {
    private val local = MutableStateFlow(Local())
    private val frames = ArrayList<Float>(2048)
    @Volatile private var collecting = false

    val state: StateFlow<BenchUi> = combine(repo.results(), local) { r, l ->
        BenchUi(l.running, l.progress, r, l.selected, l.hot, l.error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BenchUi())

    /** The graphics test canvas is shown only while this is true. */
    val graphicsRunning: Boolean get() = local.value.running == BenchType.Graphics
    fun onFrame(ms: Float) { if (collecting) frames += ms }

    fun run(type: BenchType) {
        if (local.value.running != null) return
        val temp = batteryTemp()
        if (temp != null && temp >= HOT_LIMIT_C) { local.update { it.copy(hot = temp, error = null) }; return }
        local.update { it.copy(running = type, progress = 0f, hot = null, error = null) }
        viewModelScope.launch {
            try {
                val progress: (Float) -> Unit = { p -> local.update { it.copy(progress = p) } }
                val outcome = when (type) {
                    BenchType.Cpu -> Benchmarks.cpu(onProgress = progress)
                    BenchType.Ram -> Benchmarks.ram(onProgress = progress)
                    BenchType.Storage -> Benchmarks.storage(File(context.cacheDir, "bench").also { it.mkdirs() }, onProgress = progress)
                    BenchType.Graphics -> graphics()
                }
                save(outcome, temp)
            } catch (e: Throwable) {
                local.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                collecting = false
                local.update { it.copy(running = null, progress = 0f) }
            }
        }
    }

    private suspend fun graphics(): BenchOutcome {
        frames.clear(); collecting = true
        repeat(GRAPHICS_SECONDS * 4) { i -> delay(250); local.update { it.copy(progress = (i + 1) / (GRAPHICS_SECONDS * 4f)) } }
        collecting = false
        val s = FrameStats.from(frames.toList()) ?: throw IllegalStateException("no frames")
        fun f(v: Float?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
        return BenchOutcome(BenchType.Graphics, s.avgFps.toDouble(), "FPS", linkedMapOf(
            "avg" to f(s.avgFps), "min" to f(s.minFps), "low1" to f(s.onePercentLowFps), "frameMs" to f(s.avgFrameMs),
            "stability" to f(s.stabilityPercent) + "%", "shapes" to "1500", "seconds" to GRAPHICS_SECONDS.toString(),
        ))
    }

    private suspend fun save(o: BenchOutcome, startTemp: Float?) {
        val details = LinkedHashMap(o.details)
        startTemp?.let { details["startTemp"] = String.format(Locale.US, "%.1f", it) }
        repo.save(BenchmarkResultEntity(
            type = o.type.name, score = o.score, unit = o.unit, details = BenchCodec.encode(details),
            timestamp = System.currentTimeMillis(), deviceInfo = deviceInfo(),
            profile = engineState.load()?.profile?.name,
        ))
    }

    fun toggle(id: Long) = local.update { l -> l.copy(selected = if (id in l.selected) l.selected - id else (l.selected + id).takeLast(2)) }
    fun clear() { viewModelScope.launch { repo.clear(); local.update { it.copy(selected = emptyList()) } } }

    private fun batteryTemp(): Float? {
        val i: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (t == Int.MIN_VALUE) null else t / 10f
    }

    companion object {
        fun deviceInfo(): String {
            val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE
            return "${Build.MANUFACTURER} ${Build.MODEL} · $soc · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        }
    }
}
