package app.framex.core.benchmark

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class BenchType { Cpu, Ram, Storage, Graphics }

data class BenchOutcome(
    val type: BenchType,
    val score: Double,
    val unit: String,
    /** Sub-results and test conditions, in display order. */
    val details: LinkedHashMap<String, String>,
)

object BenchCodec {
    fun encode(d: Map<String, String>) = d.entries.joinToString("\n") { "${it.key}=${it.value}" }
    fun decode(s: String): LinkedHashMap<String, String> = LinkedHashMap<String, String>().also { m ->
        s.lineSequence().filter { '=' in it }.forEach { m[it.substringBefore('=')] = it.substringAfter('=') }
    }
}

object BenchCompare {
    /** Percent change from [before] to [after]; positive = better (all FrameX scores are higher-is-better). Null if before is 0. */
    fun deltaPercent(before: Double, after: Double): Double? = if (before == 0.0) null else (after - before) / before * 100.0
}

/** Fixed, repeatable workloads. Each test runs a warm-up, then [RUNS] timed runs; the median is reported with the spread. */
object Benchmarks {
    const val RUNS = 3
    @Volatile private var sink: Long = 0 // keeps results alive so the work cannot be optimized away

    // ---- CPU ----
    fun integerWork(iterations: Int): Long {
        var x = 0x9E3779B97F4A7C15uL.toLong()
        var acc = 0L
        for (i in 0 until iterations) {
            x = x xor (x shl 13); x = x xor (x ushr 7); x = x xor (x shl 17)
            acc += x and 0xFF
        }
        return acc
    }

    fun floatWork(n: Int): Double {
        var s = 0.0
        for (i in 1..n) s += sqrt(i.toDouble()) * sin(i * 0.001)
        return s
    }

    fun sieve(limit: Int): Int {
        val composite = BooleanArray(limit + 1)
        var count = 0
        for (i in 2..limit) {
            if (!composite[i]) { count++; var j = i.toLong() * i; while (j <= limit) { composite[j.toInt()] = true; j += i } }
        }
        return count
    }

    fun cpuSet(scale: Int = 1) {
        sink += integerWork(40_000_000 / scale)
        sink += floatWork(3_000_000 / scale).toLong()
        sink += sieve(2_000_000 / scale)
    }

    suspend fun cpu(scale: Int = 1, threads: Int = Runtime.getRuntime().availableProcessors(), onProgress: (Float) -> Unit = {}): BenchOutcome =
        withContext(Dispatchers.Default) {
            cpuSet(scale * 8) // warm-up
            val single = ArrayList<Double>()
            repeat(RUNS) { single += timeSeconds { cpuSet(scale) }; onProgress((it + 1) / (RUNS * 2f)) }
            val multi = ArrayList<Double>()
            repeat(RUNS) {
                multi += timeSeconds { runBlockingParallel(threads) { cpuSet(scale) } }
                onProgress(0.5f + (it + 1) / (RUNS * 2f))
            }
            // 1000 points = the fixed workload finished in one second on one core
            val s = 1000.0 / median(single)
            val m = threads * 1000.0 / median(multi)
            BenchOutcome(BenchType.Cpu, s, "points", linkedMapOf(
                "single" to fmt(s), "multi" to fmt(m), "threads" to threads.toString(),
                "spread" to fmt(spreadPercent(single)) + "%", "runs" to RUNS.toString(),
            ))
        }

    private suspend fun runBlockingParallel(n: Int, block: () -> Unit) = coroutineScope {
        (0 until n).map { async(Dispatchers.Default) { block() } }.awaitAll()
    }

    // ---- RAM ----
    suspend fun ram(megabytes: Int = 32, passes: Int = 10, onProgress: (Float) -> Unit = {}): BenchOutcome = withContext(Dispatchers.Default) {
        val bytes = megabytes * 1024 * 1024
        val a = ByteArray(bytes).also { Random(1).nextBytes(it) }
        val b = ByteArray(bytes)
        val longs = LongArray(bytes / 8) { it.toLong() }
        val totalMb = megabytes.toDouble() * passes
        fun mbps(sec: Double) = totalMb / sec
        System.arraycopy(a, 0, b, 0, bytes) // warm-up
        val copy = ArrayList<Double>(); val read = ArrayList<Double>(); val write = ArrayList<Double>()
        repeat(RUNS) {
            copy += mbps(timeSeconds { repeat(passes) { System.arraycopy(a, 0, b, 0, bytes) } })
            read += mbps(timeSeconds { var s = 0L; repeat(passes) { for (v in longs) s += v }; sink += s })
            write += mbps(timeSeconds { repeat(passes) { longs.fill(it.toLong()) } })
            onProgress((it + 1) / RUNS.toFloat())
        }
        sink += b[bytes / 2]
        val c = median(copy)
        BenchOutcome(BenchType.Ram, c, "MB/s", linkedMapOf(
            "copy" to fmt(c), "read" to fmt(median(read)), "write" to fmt(median(write)),
            "buffer" to "$megabytes MB", "spread" to fmt(spreadPercent(copy)) + "%", "runs" to RUNS.toString(),
        ))
    }

    // ---- Storage ----
    suspend fun storage(dir: File, megabytes: Int = 64, onProgress: (Float) -> Unit = {}): BenchOutcome = withContext(Dispatchers.IO) {
        val chunk = ByteArray(1024 * 1024).also { Random(2).nextBytes(it) } // random, so compression cannot cheat
        val file = File(dir, "framex_bench.tmp")
        val writes = ArrayList<Double>(); val reads = ArrayList<Double>()
        try {
            repeat(RUNS) {
                writes += megabytes / timeSeconds {
                    FileOutputStream(file).use { out -> repeat(megabytes) { out.write(chunk) }; out.fd.sync() }
                }
                reads += megabytes / timeSeconds {
                    FileInputStream(file).use { inp -> val buf = ByteArray(1024 * 1024); var n = 0L; while (true) { val r = inp.read(buf); if (r < 0) break; n += r }; sink += n }
                }
                onProgress((it + 1) / RUNS.toFloat())
            }
        } finally { file.delete() }
        val w = median(writes)
        BenchOutcome(BenchType.Storage, w, "MB/s", linkedMapOf(
            "write" to fmt(w), "read" to fmt(median(reads)), "file" to "$megabytes MB",
            "spread" to fmt(spreadPercent(writes)) + "%", "runs" to RUNS.toString(),
        ))
    }

    private inline fun timeSeconds(block: () -> Unit): Double {
        val t0 = System.nanoTime(); block(); return (System.nanoTime() - t0) / 1e9
    }

    fun median(v: List<Double>): Double = v.sorted().let { it[it.size / 2] }
    fun spreadPercent(v: List<Double>): Double = if (v.isEmpty() || median(v) == 0.0) 0.0 else (v.max() - v.min()) / median(v) * 100.0
    fun fmt(d: Double): String = String.format(java.util.Locale.US, "%.1f", d)
}
