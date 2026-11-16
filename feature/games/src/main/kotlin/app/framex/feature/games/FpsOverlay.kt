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
import android.view.WindowManager
import android.widget.TextView

/**
 * Small draggable box drawn over other apps (needs the "draw over other apps" permission, no root).
 * It only displays text it is given; it never measures anything itself.
 */
class FpsOverlay(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private var lastText = ""

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
        alpha = 0.8f
    }

    fun show() {
        handler.post {
            if (view != null) return@post
            val density = context.resources.displayMetrics.density
            val tv = TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.MONOSPACE
                val p = (8 * density).toInt()
                setPadding(p, p / 2, p, p / 2)
                background = GradientDrawable().apply { setColor(0x99000000.toInt()); cornerRadius = 10 * density }
                text = lastText
                var startX = 0; var startY = 0; var downX = 0f; var downY = 0f
                setOnTouchListener { _, e ->
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> { startX = params.x; startY = params.y; downX = e.rawX; downY = e.rawY }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = startX + (e.rawX - downX).toInt()
                            params.y = startY + (e.rawY - downY).toInt()
                            runCatching { wm.updateViewLayout(this, params) }
                        }
                    }
                    true
                }
            }
            try { wm.addView(tv, params); view = tv } catch (_: Exception) { view = null }
        }
    }

    fun update(text: String) {
        lastText = text
        handler.post { view?.text = text }
    }

    fun hide() {
        handler.post {
            view?.let { runCatching { wm.removeView(it) } }
            view = null
        }
    }
}
