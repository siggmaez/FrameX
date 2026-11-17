package app.framex.core.adb

import android.content.Context
import android.content.Intent
import android.os.Build
import app.framex.core.optimization.ShellResult
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.security.PrivateKey
import java.security.cert.Certificate
import javax.inject.Inject
import javax.inject.Singleton

enum class AdbState { Disconnected, Connecting, Connected, NeedsPairing, NotFound, Failed }

data class AdbStatus(val state: AdbState = AdbState.Disconnected, val detail: String? = null)

private class FrameXAdbManager(context: Context) : AbsAdbConnectionManager() {
    private val keys = AdbKeys(context)
    init { setApi(Build.VERSION.SDK_INT); setHostAddress("127.0.0.1") }
    override fun getPrivateKey(): PrivateKey = keys.privateKey
    override fun getCertificate(): Certificate = keys.certificate
    override fun getDeviceName(): String = "FrameX"
}

/**
 * FrameX's own connection to this phone's wireless debugging. It talks only to this phone (127.0.0.1 / its own
 * Wi-Fi address), never to the internet, and gives the same shell access Shizuku would.
 */
@Singleton
class AdbController @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("framex_adb", Context.MODE_PRIVATE)
    private val manager: AbsAdbConnectionManager by lazy { FrameXAdbManager(context) }
    private val _status = MutableStateFlow(AdbStatus())
    val status: StateFlow<AdbStatus> = _status.asStateFlow()
    @Volatile private var connected = false

    fun isConnected() = connected
    /** True once pairing worked at least once on this install. */
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

    /** Connects by finding the wireless-debugging port automatically, or to [port] if given (shown on the Wireless debugging screen). */
    suspend fun connect(port: Int? = null): Boolean = withContext(Dispatchers.IO) {
        if (connected && manager.isConnected) return@withContext true
        _status.value = AdbStatus(AdbState.Connecting)
        try {
            if (port != null) manager.connect("127.0.0.1", port) else manager.connectTls(context, DISCOVERY_MS)
            connected = manager.isConnected
            if (connected) prefs.edit().putBoolean(KEY_PAIRED, true).apply()
            _status.value = if (connected) AdbStatus(AdbState.Connected) else AdbStatus(AdbState.Failed)
        } catch (e: AdbPairingRequiredException) {
            connected = false
            _status.value = AdbStatus(AdbState.NeedsPairing, e.message)
        } catch (e: InterruptedException) {
            connected = false
            _status.value = AdbStatus(AdbState.NotFound, e.message) // nothing announced itself: wireless debugging off, no Wi-Fi, or mDNS blocked
        } catch (e: Exception) {
            connected = false
            _status.value = AdbStatus(AdbState.Failed, e.message ?: e.javaClass.simpleName)
        }
        connected
    }

    /** Reconnects quietly if this phone was paired before and the link dropped. */
    suspend fun ensureConnected() { if (!connected && hasPaired()) connect() }

    fun disconnect() {
        runCatching { manager.disconnect() }
        connected = false
        _status.value = AdbStatus(AdbState.Disconnected)
    }

    /** Runs one already-validated command (see Commands in core:shizuku). */
    suspend fun run(command: String): ShellResult = withContext(Dispatchers.IO) {
        if (!connected) return@withContext ShellResult(false, "wireless debugging is not connected")
        try {
            withTimeout(8_000) {
                val stream = manager.openStream("shell:" + AdbOutput.wrap(command))
                try {
                    AdbOutput.parse(stream.openInputStream().use { String(it.readBytes(), Charsets.UTF_8) })
                } finally { runCatching { stream.close() } }
            }
        } catch (e: Exception) {
            if (e is java.io.IOException) { connected = false; _status.value = AdbStatus(AdbState.Disconnected, e.message) }
            ShellResult(false, e.message ?: e.javaClass.simpleName)
        }
    }

    private companion object {
        const val KEY_PAIRED = "paired"
        const val DISCOVERY_MS = 6000L
    }
}
