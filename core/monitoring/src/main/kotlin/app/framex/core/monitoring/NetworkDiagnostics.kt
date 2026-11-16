package app.framex.core.monitoring

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.framex.core.common.Metric
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.random.Random

enum class PingTarget(val label: String, val host: String) {
    Cloudflare("Cloudflare 1.1.1.1", "1.1.1.1"),
    Google("Google 8.8.8.8", "8.8.8.8"),
}

data class PingStats(
    val sent: Int, val received: Int,
    val minMs: Float?, val avgMs: Float?, val maxMs: Float?, val jitterMs: Float?,
) {
    val lossPercent: Float get() = if (sent == 0) 0f else (sent - received) * 100f / sent

    companion object {
        /** [samplesMs]: round-trip time per attempt, null for a lost attempt. Jitter = mean |Δ| of consecutive replies. */
        fun from(samplesMs: List<Float?>): PingStats {
            val ok = samplesMs.filterNotNull()
            val jitter = if (ok.size >= 2) ok.zipWithNext { a, b -> abs(b - a) }.average().toFloat() else null
            return PingStats(
                sent = samplesMs.size, received = ok.size,
                minMs = ok.minOrNull(), avgMs = if (ok.isEmpty()) null else ok.average().toFloat(),
                maxMs = ok.maxOrNull(), jitterMs = jitter,
            )
        }
    }
}

data class DnsLookup(val domain: String, val ms: Float?)

data class DnsResult(val lookups: List<DnsLookup>) {
    val avgMs: Float? get() = lookups.mapNotNull { it.ms }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
}

/**
 * Internet-connectivity diagnostics. Run on demand only.
 * These measure TCP connect time to a public server and system-resolver lookups,
 * NOT the latency to any game server.
 */
@Singleton
class NetworkDiagnostics @Inject constructor(@ApplicationContext private val context: Context) {

    fun connection(): Metric<String> = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        if (caps == null) Metric.Unavailable("No active network")
        else {
            val kind = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Other"
            }
            val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            Metric.Available(kind + if (ok) " · internet validated by Android" else " · internet not validated")
        }
    } catch (e: Exception) { Metric.Unavailable("Connection state not available") }

    suspend fun ping(target: PingTarget, count: Int = 10, timeoutMs: Int = 2000): PingStats = withContext(Dispatchers.IO) {
        val samples = ArrayList<Float?>(count)
        repeat(count) {
            samples += tcpRtt(target.host, 443, timeoutMs)
            delay(150)
        }
        PingStats.from(samples)
    }

    private fun tcpRtt(host: String, port: Int, timeoutMs: Int): Float? = try {
        Socket().use { s ->
            val t0 = System.nanoTime()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            (System.nanoTime() - t0) / 1_000_000f
        }
    } catch (_: Exception) { null }

    /** Times the system resolver with unique (uncached) names; a negative answer still measures the round trip. */
    suspend fun dnsBenchmark(count: Int = 5): DnsResult = withContext(Dispatchers.IO) {
        val domains = listOf("google.com", "cloudflare.com", "wikipedia.org")
        val out = ArrayList<DnsLookup>()
        repeat(count) { i ->
            val base = domains[i % domains.size]
            val name = "fx${Random.nextInt(1_000_000, 9_999_999)}.$base"
            val t0 = System.nanoTime()
            var ms: Float? = (System.nanoTime() - t0) / 1_000_000f
            try {
                InetAddress.getByName(name)
                ms = (System.nanoTime() - t0) / 1_000_000f
            } catch (_: java.net.UnknownHostException) {
                ms = (System.nanoTime() - t0) / 1_000_000f // NXDOMAIN reply: resolver round trip completed
            } catch (_: Exception) { ms = null }
            out += DnsLookup(base, ms)
        }
        DnsResult(out)
    }
}
