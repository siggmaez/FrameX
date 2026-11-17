package app.framex.core.optimization

import android.content.ComponentName
import android.content.Context

enum class StopResult { Stopped, NotRunning, NotConfirmed, Failed }

/** What the optional accessibility helper offers. The helper lives in a separate build so the main APK has no accessibility service. */
interface ForceStopAutomation {
    suspend fun forceStop(pkg: String): StopResult
    fun beginCover(total: Int)
    fun updateCover(index: Int, total: Int, label: String)
    fun endCover()
}

object AutomationHolder {
    @Volatile var service: ForceStopAutomation? = null
    @Volatile var cancelRequested = false

    /** True only in the "boost" build, which includes the accessibility helper. */
    fun isBundled(context: Context): Boolean = try {
        context.packageManager.getServiceInfo(ComponentName(context.packageName, "app.framex.core.a11yboost.ForceStopAccessibilityService"), 0)
        true
    } catch (_: Exception) { false }
}
