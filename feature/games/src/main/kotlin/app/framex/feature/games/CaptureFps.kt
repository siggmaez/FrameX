package app.framex.feature.games

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import app.framex.core.monitoring.FrameCollector
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FPS ESTIMATE for apps FrameX cannot read directly. With the user's screen-capture consent, FrameX mirrors the
 * screen into a tiny hidden surface and counts how often the picture really changes. That is the rate at which new
 * frames reach the display, but it is an estimate: other screen changes (clock, our own overlay text) add a little,
 * and capturing costs a little performance. It is always labelled "estimated".
 */
@Singleton
class CaptureFpsController @Inject constructor(@ApplicationContext private val context: Context) {
    private val lock = Any()
    private var frames = FrameCollector()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    @Volatile var startedAtMs = 0L
        private set

    internal fun setRunning(v: Boolean) { if (v) startedAtMs = System.currentTimeMillis(); _running.value = v }
    internal fun onFrame(tsNs: Long) { synchronized(lock) { frames.addSample(tsNs) } }

    /** Forget frames seen so far (before the game started). */
    fun reset() { synchronized(lock) { frames = FrameCollector() } }
    fun currentFps(): Float? = synchronized(lock) { frames.currentFps() }
    fun frameTimes(): List<Float> = synchronized(lock) { frames.frameTimesMs.toList() }

    fun start(resultCode: Int, data: Intent) {
        context.startForegroundService(Intent(context, FpsCaptureService::class.java).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data))
    }
    fun stop() { context.stopService(Intent(context, FpsCaptureService::class.java)) }

    internal companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
    }
}

@AndroidEntryPoint
class FpsCaptureService : Service() {
    @Inject lateinit var controller: CaptureFpsController

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra(CaptureFpsController.EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(CaptureFpsController.EXTRA_DATA)
        if (data == null || projection != null) { if (projection == null) stopSelf(); return START_NOT_STICKY }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.capture_channel), NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_framex).setContentTitle("FrameX")
            .setContentText(getString(R.string.capture_notif)).setOngoing(true).build()
        startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(code, data)
            projection = mp
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, Handler(mainLooper))

            val t = HandlerThread("framex-fps-capture").also { it.start() }
            thread = t
            val m = resources.displayMetrics
            val w = (m.widthPixels / SCALE).coerceAtLeast(64)
            val h = (m.heightPixels / SCALE).coerceAtLeast(64)
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            reader = r
            r.setOnImageAvailableListener({ ir ->
                ir.acquireLatestImage()?.close()
                controller.onFrame(SystemClock.elapsedRealtimeNanos())
            }, Handler(t.looper))
            display = mp.createVirtualDisplay("FrameX-fps", w, h, m.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
            controller.reset()
            controller.setRunning(true)
        } catch (e: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        controller.setRunning(false)
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        thread?.quitSafely()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "fps_capture"
        const val ID = 1002
        const val SCALE = 6 // capture at 1/6 size to keep the cost low
    }
}
