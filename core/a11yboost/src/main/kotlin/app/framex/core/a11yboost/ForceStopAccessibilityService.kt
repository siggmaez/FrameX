package app.framex.core.a11yboost

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.framex.core.optimization.AutomationHolder
import app.framex.core.optimization.ForceStopAutomation
import app.framex.core.optimization.StopResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull



/**
 * Optional. Opens the system "App info" page of one app at a time and presses "Force stop" and its confirmation,
 * exactly like tapping them yourself, then checks Android's own "stopped" flag. It only reacts while a boost is
 * running, only inside the Settings app, and never touches anything else. A full-screen progress cover with a
 * Cancel button is shown meanwhile.
 */
class ForceStopAccessibilityService : AccessibilityService(), ForceStopAutomation {
    private enum class Stage { Idle, WaitDetails, WaitConfirm }

    @Volatile private var stage = Stage.Idle
    @Volatile private var done: CompletableDeferred<StopResult>? = null
    private val main = Handler(Looper.getMainLooper())
    private var cover: LinearLayout? = null
    private var title: TextView? = null
    private var line: TextView? = null

    override fun onServiceConnected() { super.onServiceConnected(); AutomationHolder.service = this }
    override fun onUnbind(intent: Intent?): Boolean { AutomationHolder.service = null; endCoverImpl(); return super.onUnbind(intent) }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (stage == Stage.Idle) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() !in ALLOWED_PACKAGES) return // never act outside Settings
        when (stage) {
            Stage.WaitDetails -> {
                val btn = findButton(root, FORCE_STOP_TEXTS, exclude = emptyList())
                if (btn != null) {
                    if (!btn.isEnabled) { finish(StopResult.NotRunning); return } // Android greys it out when nothing is running
                    stage = Stage.WaitConfirm
                    btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
            Stage.WaitConfirm -> {
                val ok = findButton(root, CONFIRM_TEXTS, exclude = CANCEL_TEXTS)
                if (ok != null && ok.isEnabled) { ok.performAction(AccessibilityNodeInfo.ACTION_CLICK); finish(StopResult.Stopped) }
            }
            Stage.Idle -> {}
        }
    }

    /** First enabled-or-greyed clickable node whose text contains one of [texts] and none of [exclude]. */
    private fun findButton(root: AccessibilityNodeInfo, texts: List<String>, exclude: List<String>): AccessibilityNodeInfo? {
        for (t in texts) {
            for (n in root.findAccessibilityNodeInfosByText(t).orEmpty()) {
                val label = n.text?.toString()?.lowercase().orEmpty()
                if (exclude.any { label.contains(it) }) continue
                var c: AccessibilityNodeInfo? = n
                while (c != null && !c.isClickable) c = c.parent
                if (c != null && c.isClickable) return c
            }
        }
        return null
    }

    private fun finish(r: StopResult) { done?.complete(r) }

    /** Force-stops [pkg] through the Settings UI. The result is verified against Android's "stopped" flag. */
    override suspend fun forceStop(pkg: String): StopResult {
        val d = CompletableDeferred<StopResult>()
        done = d
        stage = Stage.WaitDetails
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { stage = Stage.Idle; return StopResult.Failed }
        val r = withTimeoutOrNull(PER_APP_TIMEOUT_MS) { d.await() }
        stage = Stage.Idle
        delay(350)
        performGlobalAction(GLOBAL_ACTION_BACK)
        delay(250)
        return when {
            r == StopResult.NotRunning -> StopResult.NotRunning
            isStopped(pkg) -> StopResult.Stopped
            r == null -> StopResult.Failed
            else -> StopResult.NotConfirmed
        }
    }

    private fun isStopped(pkg: String) = runCatching {
        packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_STOPPED != 0
    }.getOrDefault(false)


    override fun beginCover(total: Int) { beginCoverImpl(total) }
    override fun updateCover(index: Int, total: Int, label: String) { updateCoverImpl(index, total, label) }
    override fun endCover() { endCoverImpl() }
    // ---- progress cover ----
    private fun beginCoverImpl(total: Int) = main.post {
        if (cover != null) return@post
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density
        val t = TextView(this).apply { setTextColor(Color.WHITE); textSize = 22f; gravity = Gravity.CENTER; text = getString(R.string.a11y_cover_title) }
        val l = TextView(this).apply { setTextColor(0xFFB0BEC5.toInt()); textSize = 15f; gravity = Gravity.CENTER; setPadding(0, (12 * d).toInt(), 0, (24 * d).toInt()) }
        val cancel = Button(this).apply { text = getString(R.string.a11y_cover_cancel); setOnClickListener { AutomationHolder.cancelRequested = true } }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(0xF2101820.toInt()); setPadding((32 * d).toInt(), 0, (32 * d).toInt(), 0)
            addView(t); addView(l); addView(cancel)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, // settings stays the active window
            PixelFormat.TRANSLUCENT,
        )
        runCatching { wm.addView(box, lp); cover = box; title = t; line = l }
    }

    private fun updateCoverImpl(index: Int, total: Int, label: String) = main.post { line?.text = getString(R.string.a11y_cover_line, index, total, label) }

    private fun endCoverImpl() = main.post {
        cover?.let { runCatching { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) } }
        cover = null
    }

    private companion object {
        const val PER_APP_TIMEOUT_MS = 7000L
        val ALLOWED_PACKAGES = setOf("com.android.settings", "com.miui.securitycenter")
        // Matches the system UI in the languages FrameX supports; the Settings app uses the phone's language.
        val FORCE_STOP_TEXTS = listOf("force stop", "принудительно остановить", "остановить", "завершить")
        val CONFIRM_TEXTS = listOf("force stop", "принудительно остановить", "ok", "ок", "остановить", "завершить", "yes", "да")
        val CANCEL_TEXTS = listOf("cancel", "отмена", "no", "нет")
    }
}
