package app.framex.core.monitoring

import kotlin.math.ceil

/** Where an FPS number came from. Never mixed. */
enum class FpsSource { FrameXTest, SurfaceFlinger }

/**
 * Measured = taken from real frame timestamps. Estimated = derived indirectly (no source produces this today).
 * Unavailable = cannot be measured here. FrameX never shows a made-up value.
 */
sealed interface FpsReading {
    data class Measured(val source: FpsSource, val stats: FrameStats) : FpsReading
    data class Estimated(val source: FpsSource, val fps: Float) : FpsReading
    data class Unavailable(val reason: String) : FpsReading
}

data class FrameStats(
    val frames: Int,
    val durationMs: Float,
    val avgFps: Float,
    /** Frames in the last second; null when less than one second of data. */
    val currentFps: Float?,
    /** Lowest FPS over any full 1-second window; null when there is no full second. */
    val minFps: Float?,
    /** Average of the slowest 1% of frames, as FPS; null below 100 frames (not enough samples). */
    val onePercentLowFps: Float?,
    val avgFrameMs: Float,
    val maxFrameMs: Float,
    /** Share of frames that were not janky (frame time ≤ 1.5 × median), 0..100. */
    val stabilityPercent: Float,
) {
    companion object {
        fun from(frameTimesMs: List<Float>): FrameStats? {
            val n = frameTimesMs.size
            if (n < 2) return null
            val total = frameTimesMs.sum()
            if (total <= 0f) return null
            val avgMs = total / n

            // current: frames within the last 1000 ms
            var acc = 0f
            var cnt = 0
            for (i in n - 1 downTo 0) { acc += frameTimesMs[i]; cnt++; if (acc >= 1000f) break }
            val current = if (acc >= 1000f) cnt * 1000f / acc else null

            // min: frames per full 1-second bucket
            val buckets = HashMap<Int, Int>()
            var cum = 0f
            for (ft in frameTimesMs) { cum += ft; buckets.merge((cum / 1000f).toInt(), 1, Int::plus) }
            val lastBucket = (total / 1000f).toInt()
            val full = buckets.filterKeys { it < lastBucket }
            val minFps = if (full.isEmpty()) null else full.values.min().toFloat()

            val low = if (n >= 100) {
                val worst = frameTimesMs.sortedDescending().take(ceil(n * 0.01).toInt())
                1000f / worst.average().toFloat()
            } else null

            val median = frameTimesMs.sorted()[n / 2]
            val jank = frameTimesMs.count { it > 1.5f * median }
            return FrameStats(
                frames = n, durationMs = total, avgFps = 1000f / avgMs, currentFps = current, minFps = minFps,
                onePercentLowFps = low, avgFrameMs = avgMs, maxFrameMs = frameTimesMs.max(),
                stabilityPercent = 100f * (1f - jank.toFloat() / n),
            )
        }
    }
}

/** Parses `dumpsys SurfaceFlinger --latency <layer>` output into "actual present" timestamps (ns). */
object SurfaceFlingerLatencyParser {
    private const val PENDING = Long.MAX_VALUE

    fun parse(text: String): List<Long> =
        text.lineSequence().drop(1).mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+"))
            if (p.size < 3) null else p[1].toLongOrNull()?.takeIf { it > 0 && it != PENDING }
        }.toList()
}

object SurfaceFlingerLayers {
    /** Picks the layer of [pkg] that carries the game's frames, or null. */
    fun pick(layers: List<String>, pkg: String): String? {
        val mine = layers.filter { it.contains(pkg) && !it.startsWith("Background for") && !it.startsWith("Bounds for") }
        return mine.lastOrNull { it.startsWith("SurfaceView") } ?: mine.lastOrNull()
    }
}

/** Accumulates frame times from repeated, overlapping latency dumps without counting a frame twice. */
class FrameCollector {
    private var lastTs = -1L
    private val times = ArrayList<Float>()
    val frameTimesMs: List<Float> get() = times

    fun add(timestampsNs: List<Long>) {
        for (ts in timestampsNs.sorted()) {
            if (ts <= lastTs) continue
            if (lastTs > 0) {
                val ms = (ts - lastTs) / 1_000_000f
                if (ms < MAX_GAP_MS) times += ms // longer gaps are pauses, not frames
            }
            lastTs = ts
        }
    }

    private companion object { const val MAX_GAP_MS = 5000f }
}
