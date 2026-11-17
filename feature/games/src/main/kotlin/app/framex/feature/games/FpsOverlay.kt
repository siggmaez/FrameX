package app.framex.feature.games

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** What the overlay shows. Colors are only a visual hint; the numbers are whatever the caller measured or estimated. */
data class OverlayContent(
    val fps: String,
    val fpsUnit: String,
    val fpsColor: Int,
    val temp: String,
    val tempColor: Int,
)

object OverlayColors {
    const val GOOD = 0xFF7FF0CF.toInt()
    const val WARN = 0xFFFFC857.toInt()
    const val BAD = 0xFFFF6B6B.toInt()
    const val NEUTRAL = 0xFFB0BEC5.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()

    fun forFps(fps: Int?): Int = when {
        fps == null -> NEUTRAL
        fps >= 50 -> GOOD
        fps >= 25 -> WARN
        else -> BAD
    }

    /** Same thresholds as FrameX's temperature advice: 40 °C warm, 45 °C hot. */
    fun forTemp(c: Float?): Int = when {
        c == null -> NEUTRAL
        c >= 45f -> BAD
        c >= 40f -> WARN
        else -> TEXT
    }
}

/**
 * Small draggable pill drawn over other apps (needs "draw over other apps", no root).
 * Two rows with their own icons: FPS gauge and thermometer. It only displays what it is given.
 */
class FpsOverlay(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private var root: LinearLayout? = null
    private var fpsIcon: ImageView? = null
    private var fpsValue: TextView? = null
    private var fpsUnit: TextView? = null
    private var tempIcon: ImageView? = null
    private var tempValue: TextView? = null
    private var last: OverlayContent? = null

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 24
        y = 120
        alpha = 0.9f
    }

    private fun dp(v: Int) = (v * density).toInt()

    private fun row(iconRes: Int): Triple<LinearLayout, ImageView, TextView> {
        val icon = ImageView(context).apply { setImageResource(iconRes); layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)) }
        val value = TextView(context).apply {
            setTextColor(OverlayColors.TEXT); textSize = 16f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(dp(6), 0, 0, 0)
        }
        val r = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(icon); addView(value)
        }
        return Triple(r, icon, value)
    }

    fun show() {
        handler.post {
            if (root != null) return@post
            val (fpsRow, fi, fv) = row(R.drawable.ic_overlay_fps)
            val unit = TextView(context).apply { setTextColor(OverlayColors.NEUTRAL); textSize = 10f; setPadding(dp(4), dp(4), 0, 0) }
            fpsRow.addView(unit)
            val (tempRow, ti, tv) = row(R.drawable.ic_overlay_temp)
            val box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(7), dp(12), dp(7))
                background = GradientDrawable().apply {
                    setColor(0xD9101820.toInt()); cornerRadius = dp(14).toFloat(); setStroke(dp(1), 0x33FFFFFF)
                }
                addView(fpsRow, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(tempRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
                var startX = 0; var startY = 0; var downX = 0f; var downY = 0f
                setOnTouchListener { v, e ->
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> { startX = params.x; startY = params.y; downX = e.rawX; downY = e.rawY }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = startX + (e.rawX - downX).toInt()
                            params.y = startY + (e.rawY - downY).toInt()
                            runCatching { wm.updateViewLayout(v, params) }
                        }
                    }
                    true
                }
            }
            fpsIcon = fi; fpsValue = fv; fpsUnit = unit; tempIcon = ti; tempValue = tv
            try { wm.addView(box, params); root = box; last?.let { apply(it) } } catch (_: Exception) { root = null }
        }
    }

    private fun apply(c: OverlayContent) {
        fpsValue?.text = c.fps; fpsValue?.setTextColor(c.fpsColor); fpsIcon?.setColorFilter(c.fpsColor)
        fpsUnit?.text = c.fpsUnit
        tempValue?.text = c.temp; tempValue?.setTextColor(c.tempColor); tempIcon?.setColorFilter(c.tempColor)
    }

    fun update(c: OverlayContent) {
        last = c
        handler.post { apply(c) }
    }

    fun hide() {
        handler.post {
            root?.let { runCatching { wm.removeView(it) } }
            root = null
        }
    }
}
