package app.framex.core.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Short-lived helper. The system's "Pair device with pairing code" window closes as soon as you leave it, so the
 * code is typed into a notification instead. Finds the pairing window on this phone, asks for the 6-digit code,
 * pairs, connects, and stops.
 */
@AndroidEntryPoint
class AdbPairingService : Service() {
    @Inject lateinit var adb: AdbController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var mdns: AdbMdns? = null
    private var foregroundStarted = false
    @Volatile private var host: String? = null
    @Volatile private var port = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.adbp_channel), NotificationManager.IMPORTANCE_HIGH))
        if (intent?.action == ACTION_REPLY) {
            val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()?.filter { it.isDigit() }
            handleCode(code)
        } else {
            startSearching()
        }
        return START_NOT_STICKY
    }

    private fun startSearching() {
        showForeground(base(getString(R.string.adbp_searching)).setOngoing(true).build())
        mdns?.stop()
        host = null; port = -1
        mdns = AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { address, p ->
            if (address != null && p > 0) { host = address.hostAddress; port = p; promptForCode() }
        }.also { it.start() }
        scope.launch {
            delay(SEARCH_TIMEOUT_MS)
            if (port < 0) finish(getString(R.string.adbp_timeout))
        }
    }

    private fun promptForCode() {
        val input = RemoteInput.Builder(KEY_CODE).setLabel(getString(R.string.adbp_input_label)).build()
        val reply = PendingIntent.getService(
            this, 0, Intent(this, AdbPairingService::class.java).setAction(ACTION_REPLY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE, // RemoteInput needs a mutable intent
        )
        val action = Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_adb), getString(R.string.adbp_action), reply)
            .addRemoteInput(input).build()
        notify(base(getString(R.string.adbp_found)).setOngoing(true).addAction(action).build())
    }

    private fun handleCode(code: String?) {
        val h = host
        if (code == null || code.length != 6 || h == null || port < 0) {
            promptOrRetry(getString(R.string.adbp_bad_code)); return
        }
        notify(base(getString(R.string.adbp_pairing)).setOngoing(true).build())
        scope.launch {
            if (!adb.pair(h, port, code)) { finish(getString(R.string.adbp_pair_failed)); return@launch }
            notify(base(getString(R.string.adbp_connecting)).setOngoing(true).build())
            delay(1500) // wireless debugging needs a moment to announce its connect port after pairing
            if (adb.connect()) finish(getString(R.string.adbp_done)) else finish(getString(R.string.adbp_paired_not_connected))
        }
    }

    private fun promptOrRetry(message: String) {
        if (port > 0) promptForCode() else notify(base(message).build())
    }

    private fun finish(text: String) {
        notify(base(text).setOngoing(false).build())
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun base(text: String): Notification.Builder =
        Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_adb).setContentTitle(getString(R.string.adbp_title)).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))

    private fun showForeground(n: Notification) {
        if (foregroundStarted) { notify(n); return }
        foregroundStarted = true
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID, n)
    }

    private fun notify(n: Notification) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, n)
    }

    override fun onDestroy() {
        mdns?.stop()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "adb_pairing"
        const val ID = 1003
        const val ACTION_REPLY = "app.framex.core.adb.REPLY"
        const val KEY_CODE = "pairing_code"
        const val SEARCH_TIMEOUT_MS = 180_000L
    }
}
