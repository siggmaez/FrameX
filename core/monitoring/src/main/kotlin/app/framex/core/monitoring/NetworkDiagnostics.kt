package app.framex.core.monitoring

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import app.framex.core.common.ConnectionKind
import app.framex.core.common.NetworkMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
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

enum class NetStatus { Ok, Blocked, NoNetwork, Failed }

/** Result of testing one connection type. */
data class NetResult(
    val kind: ConnectionKind,
    val status: NetStatus,
    val connection: String? = null,
    val ping: PingStats? = null,
    val dns: DnsResult? = null,
    val message: String? = null,
)

/**
 * Internet-connectivity diagnostics. Run on demand only.
 * Each test is bound to the Wi-Fi or the mobile network you pick, so the two can be compared.
 * They measure TCP connect time to a public server and system-resolver lookups,
 * NOT the latency to any game server. In Local mode nothing here ever opens a connection.
 */
@Singleton
class NetworkDiagnostics @Inject constructor(@ApplicationContext private val context: Context) {
    private val cm get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    suspend fun diagnose(kind: ConnectionKind, target: PingTarget, mode: NetworkMode, onPingDone: (PingStats) -> Unit = {}): NetResult {
        if (!mode.allows(kind)) return NetResult(kind, NetStatus.Blocked) // enforced here, not only in the UI
        val lease = lease(kind) ?: return NetResult(kind, NetStatus.NoNetwork)
        return try {
            val info = describe(lease.network, kind)
            val ping = ping(lease.network, target)
            onPingDone(ping)
            val dns = dnsBenchmark(lease.network)
            NetResult(kind, NetStatus.Ok, info, ping, dns)
        } catch (e: Exception) {
            NetResult(kind, NetStatus.Failed, message = e.message ?: e.javaClass.simpleName)
        } finally {
            lease.close()
        }
    }

    private class Lease(val network: Network, private val cm: ConnectivityManager, private val cb: ConnectivityManager.NetworkCallback) {
        fun close() { runCatching { cm.unregisterNetworkCallback(cb) } }
    }

    /** Asks Android for the Wi-Fi or mobile network (bringing mobile data up if it is idle). Null if it is not available. */
    private suspend fun lease(kind: ConnectionKind): Lease? = suspendCancellableCoroutine { cont ->
        val transport = if (kind == ConnectionKind.Wifi) NetworkCapabilities.TRANSPORT_WIFI else NetworkCapabilities.TRANSPORT_CELLULAR
        val req = NetworkRequest.Builder().addTransportType(transport).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        val manager = cm
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { if (cont.isActive) cont.resume(Lease(network, manager, this)) }
            override fun onUnavailable() {
                runCatching { manager.unregisterNetworkCallback(this) }
                if (cont.isActive) cont.resume(null)
            }
        }
        try { manager.requestNetwork(req, cb, REQUEST_TIMEOUT_MS) } catch (e: Exception) { if (cont.isActive) cont.resume(null) }
        cont.invokeOnCancellation { runCatching { manager.unregisterNetworkCallback(cb) } }
    }

    private fun describe(network: Network, kind: ConnectionKind): String {
        val caps = cm.getNetworkCapabilities(network)
        val name = context.getString(if (kind == ConnectionKind.Wifi) R.string.net_wifi else R.string.net_mobile)
        val ok = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        return context.getString(if (ok) R.string.net_validated else R.string.net_not_validated, name)
    }

    private suspend fun ping(network: Network, target: PingTarget, count: Int = 10, timeoutMs: Int = 2000): PingStats = withContext(Dispatchers.IO) {
        val samples = ArrayList<Float?>(count)
        repeat(count) {
            samples += tcpRtt(network, target.host, 443, timeoutMs)
            delay(150)
        }
        PingStats.from(samples)
    }

    private fun tcpRtt(network: Network, host: String, port: Int, timeoutMs: Int): Float? = try {
        Socket().use { s ->
            network.bindSocket(s) // this test goes over exactly this network
            val t0 = System.nanoTime()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            (System.nanoTime() - t0) / 1_000_000f
        }
    } catch (_: Exception) { null }

    /** Times the network's resolver with unique (uncached) names; a negative answer still measures the round trip. */
    private suspend fun dnsBenchmark(network: Network, count: Int = 5): DnsResult = withContext(Dispatchers.IO) {
        val domains = listOf("google.com", "cloudflare.com", "wikipedia.org")
        val out = ArrayList<DnsLookup>()
        repeat(count) { i ->
            val base = domains[i % domains.size]
            val name = "fx${Random.nextInt(1_000_000, 9_999_999)}.$base"
            val t0 = System.nanoTime()
            val ms: Float? = try {
                network.getAllByName(name)
                (System.nanoTime() - t0) / 1_000_000f
            } catch (_: java.net.UnknownHostException) {
                (System.nanoTime() - t0) / 1_000_000f // NXDOMAIN reply: resolver round trip completed
            } catch (_: Exception) { null }
            out += DnsLookup(base, ms)
        }
        DnsResult(out)
    }

    private companion object { const val REQUEST_TIMEOUT_MS = 4000 }
}
