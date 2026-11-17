package app.framex.core.adb

import android.content.Context
import android.content.Intent
import android.os.Build
import app.framex.core.optimization.ShellResult
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.security.PrivateKey
import java.security.cert.Certificate
import javax.inject.Inject
import javax.inject.Singleton

enum class AdbState { Disconnected, Connecting, AwaitingApproval, Connected, NeedsPairing, NotFound, Failed }

/** [viaTcp] = connected through plain ADB-over-TCP (port 5555), which does not need Wi-Fi. */
data class AdbStatus(val state: AdbState = AdbState.Disconnected, val detail: String? = null, val viaTcp: Boolean = false)

private class FrameXAdbManager(context: Context) : AbsAdbConnectionManager() {
    private val keys = AdbKeys(context)
    init { setApi(Build.VERSION.SDK_INT); setHostAddress("127.0.0.1") }
    override fun getPrivateKey(): PrivateKey = keys.privateKey
    override fun getCertificate(): Certificate = keys.certificate
    override fun getDeviceName(): String = "FrameX"
}

/**
 * FrameX's own connection to this phone's ADB. It talks only to this phone (127.0.0.1 / its own Wi-Fi address),
 * never to the internet, and gives the same shell access Shizuku would.
 *
 * Wireless debugging needs Wi-Fi. After one successful connection, [enableTcpMode] switches adbd to plain TCP
 * (what `adb tcpip 5555` does), which keeps working without Wi-Fi until the phone restarts.
 */
@Singleton
class AdbController @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("framex_adb", Context.MODE_PRIVATE)
    private val manager: AbsAdbConnectionManager by lazy { FrameXAdbManager(context) }
    private val _status = MutableStateFlow(AdbStatus())
    val status: StateFlow<AdbStatus> = _status.asStateFlow()
    @Volatile private var connected = false
    @Volatile private var viaTcp = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ensureLock = Mutex()
    @Volatile private var lastAttempt = 0L

    fun isConnected() = connected
    /** True once pairing or a connection worked at least once on this install. */
    fun hasPaired() = prefs.getBoolean(KEY_PAIRED, false)

    /** Starts the short pairing helper (notification where you type the pairing code). */
    fun startPairing() { context.startForegroundService(Intent(context, AdbPairingService::class.java)) }

    suspend fun pair(host: String, port: Int, code: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val ok = manager.pair(host, port, code)
            if (ok) prefs.edit().putBoolean(KEY_PAIRED, true).apply()
            ok
        } catch (e: Exception) {
            _status.value = AdbStatus(AdbState.Failed, e.message ?: e.javaClass.simpleName)
            false
        }
    }

    private fun ok() {
        connected = manager.isConnected
        if (connected) prefs.edit().putBoolean(KEY_PAIRED, true).apply()
        _status.value = if (connected) AdbStatus(AdbState.Connected, viaTcp = viaTcp) else AdbStatus(AdbState.Failed)
    }

    private fun failed(e: Throwable) {
        connected = false
        _status.value = when (e) {
            is AdbPairingRequiredException -> AdbStatus(AdbState.NeedsPairing, e.message)
            is InterruptedException -> AdbStatus(AdbState.NotFound, e.message) // nothing announced itself: debugging off, no Wi-Fi, or mDNS blocked
            else -> AdbStatus(AdbState.Failed, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Connects by finding the wireless-debugging port automatically, or to [port] if given. */
    suspend fun connect(port: Int? = null): Boolean = withContext(Dispatchers.IO) {
        if (connected && manager.isConnected) return@withContext true
        viaTcp = port == TCP_PORT
        _status.value = AdbStatus(if (viaTcp) AdbState.AwaitingApproval else AdbState.Connecting, viaTcp = viaTcp)
        try {
            if (port != null) manager.connect("127.0.0.1", port) else manager.connectTls(context, DISCOVERY_MS)
            ok()
        } catch (e: Exception) {
            failed(e)
        }
        connected
    }

    /**
     * Switches adbd to plain TCP so the link no longer depends on Wi-Fi, then reconnects through it.
     * Android may show "Allow USB debugging?" for FrameX's key: tick "Always allow" and accept.
     */
    suspend fun enableTcpMode(): Boolean = withContext(Dispatchers.IO) {
        if (!connected) return@withContext false
        try {
            runCatching { manager.openStream("tcpip:$TCP_PORT").use { it.openInputStream().readBytes() } } // adbd restarts and drops us
        } catch (_: Exception) {}
        runCatching { manager.disconnect() }
        connected = false
        delay(3000) // adbd restarts in TCP mode
        connectTcp(waitForApproval = true)
    }

    /** Back to normal (wireless debugging / USB), closing the TCP port. */
    suspend fun disableTcpMode() = withContext(Dispatchers.IO) {
        if (connected && viaTcp) runCatching { manager.openStream("usb:").use { it.openInputStream().readBytes() } }
        runCatching { manager.disconnect() }
        connected = false
        viaTcp = false
        prefs.edit().putBoolean(KEY_TCP, false).apply()
        _status.value = AdbStatus(AdbState.Disconnected)
    }

    private suspend fun connectTcp(waitForApproval: Boolean): Boolean {
        viaTcp = true
        _status.value = AdbStatus(if (waitForApproval) AdbState.AwaitingApproval else AdbState.Connecting, viaTcp = true)
        try {
            withTimeout(if (waitForApproval) 60_000L else 8_000L) { runInterruptible(Dispatchers.IO) { manager.connect("127.0.0.1", TCP_PORT) } }
            ok()
            if (connected) prefs.edit().putBoolean(KEY_TCP, true).apply()
        } catch (e: TimeoutCancellationException) {
            viaTcp = false; connected = false
            _status.value = AdbStatus(AdbState.Failed, "no answer on port $TCP_PORT (approve the dialog on the phone)")
        } catch (e: Exception) {
            viaTcp = false
            failed(e)
        }
        return connected
    }

    /** Reconnects quietly if this phone was paired before and the link dropped: TCP first (no Wi-Fi needed), then wireless. */
    suspend fun ensureConnected() {
        if (connected || !hasPaired()) return
        if (!ensureLock.tryLock()) return // another reconnect is already running
        try {
            val now = System.currentTimeMillis()
            if (now - lastAttempt < RETRY_GAP_MS) return
            lastAttempt = now
            val wantsTcp = prefs.getBoolean(KEY_TCP, false)
            if (wantsTcp && connectTcp(waitForApproval = false)) return
            // wireless debugging can only be reached over Wi-Fi: do not wait for a scan that cannot succeed
            if (wifiConnected() && connect() && wantsTcp) enableTcpMode()
        } finally {
            if (!connected) _status.value = AdbStatus(AdbState.Disconnected)
            ensureLock.unlock()
        }
    }

    /** Fire-and-forget reconnect, for app start and other moments with no coroutine at hand. */
    fun ensureConnectedAsync() { scope.launch { ensureConnected() } }

    private fun wifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    fun disconnect() {
        runCatching { manager.disconnect() }
        connected = false
        viaTcp = false
        _status.value = AdbStatus(AdbState.Disconnected)
    }

    /** Runs one already-validated command (see Commands in core:shizuku). */
    suspend fun run(command: String): ShellResult = withContext(Dispatchers.IO) {
        if (!connected) return@withContext ShellResult(false, "ADB is not connected")
        try {
            withTimeout(20_000) {
                val stream = manager.openStream("shell:" + AdbOutput.wrap(command))
                try {
                    AdbOutput.parse(stream.openInputStream().use { String(it.readBytes(), Charsets.UTF_8) })
                } finally { runCatching { stream.close() } }
            }
        } catch (e: Exception) {
            // one broken stream is not a dropped link: only give up if ADB itself says it is no longer connected
            if (!manager.isConnected) { connected = false; _status.value = AdbStatus(AdbState.Disconnected, e.message) }
            ShellResult(false, e.message ?: e.javaClass.simpleName)
        }
    }

    private companion object {
        const val KEY_PAIRED = "paired"
        const val KEY_TCP = "tcp_mode"
        const val DISCOVERY_MS = 6000L
        const val RETRY_GAP_MS = 8_000L
        const val TCP_PORT = 5555
    }
}
