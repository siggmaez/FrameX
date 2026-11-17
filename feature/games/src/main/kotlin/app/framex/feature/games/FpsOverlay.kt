package app.framex.feature.games

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import app.framex.core.common.OverlayStyle
import app.framex.core.designsystem.overlay.OverlayContent
import app.framex.core.designsystem.overlay.OverlayView

/**
 * Draggable box drawn over other apps (needs "draw over other apps", no root).
 * It only displays what it is given; it never measures anything itself.
 */
class FpsOverlay(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: OverlayView? = null
    private var style = OverlayStyle.Stealth
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
        alpha = 0.92f
    }

    private fun attach() {
        val v = OverlayView(context, style)
        last?.let { v.setContent(it) } // the window is measured with this text already in place
        var startX = 0; var startY = 0; var downX = 0f; var downY = 0f
        v.setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { startX = params.x; startY = params.y; downX = e.rawX; downY = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - downX).toInt()
                    params.y = startY + (e.rawY - downY).toInt()
                    runCatching { wm.updateViewLayout(view, params) }
                }
            }
            true
        }
        try { wm.addView(v, params); view = v } catch (_: Exception) { view = null }
    }

    private fun detach() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }


    /**
     * Changes the content and then makes Android measure the window again. A WRAP_CONTENT overlay window can keep the
     * size of its first (empty) layout otherwise, which cut the picture and hid the numbers.
     */
    private fun relayout(change: (OverlayView) -> Unit) {
        val v = view ?: return
        change(v)
        v.requestLayout()
        runCatching { wm.updateViewLayout(v, params) }
    }
    fun show() { handler.post { if (view == null) attach() } }

    /** Switches the look; if the overlay is showing it is rebuilt in place. */
    fun setStyle(s: OverlayStyle) {
        handler.post {
            if (s == style) return@post
            style = s
            if (view != null) { detach(); attach() }
        }
    }

    fun update(c: OverlayContent) {
        last = c
        handler.post { relayout { it.setContent(c) } }
    }

    fun hide() { handler.post { detach() } }
}
