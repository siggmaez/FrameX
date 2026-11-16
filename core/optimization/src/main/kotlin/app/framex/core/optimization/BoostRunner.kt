package app.framex.core.optimization

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** How apps get stopped. The strongest available one is used, and the screen says which. */
enum class StopMethod { ShizukuForceStop, AccessibilityForceStop, BackgroundKill }

enum class ItemStatus { Pending, Working, Stopped, NotRunning, Requested, Failed }

data class BoostItem(val packageName: String, val label: String, val status: ItemStatus = ItemStatus.Pending)

data class BoostProgress(
    val method: StopMethod,
    val items: List<BoostItem>,
    val ramBeforeMb: Long,
    val ramAfterMb: Long? = null,
    val finished: Boolean = false,
    val cancelled: Boolean = false,
) {
    val done: Int get() = items.count { it.status != ItemStatus.Pending && it.status != ItemStatus.Working }
    val stopped: Int get() = items.count { it.status == ItemStatus.Stopped }
    val freedMb: Long? get() = ramAfterMb?.let { it - ramBeforeMb }
}

/**
 * Stops the given apps and publishes real progress. Nothing here is staged: each row changes only when that app's
 * stop call returned, and force-stops are checked against Android's own "stopped" flag.
 */
@Singleton
class BoostRunner @Inject constructor(@ApplicationContext private val context: Context, private val shell: PrivilegedShell) {
    private val mutex = Mutex()
    private val _progress = MutableStateFlow<BoostProgress?>(null)
    val progress: StateFlow<BoostProgress?> = _progress.asStateFlow()
    @Volatile private var cancel = false

    fun bestMethod(): StopMethod = when {
        shell.isAvailable() -> StopMethod.ShizukuForceStop
        AutomationHolder.service != null -> StopMethod.AccessibilityForceStop
        else -> StopMethod.BackgroundKill
    }

    /** How many apps one boost handles: the visible Settings automation is slower. */
    fun suggestedLimit(): Int = if (bestMethod() == StopMethod.AccessibilityForceStop) 12 else 25

    fun cancel() { cancel = true }
    fun dismiss() { _progress.value = null }

    suspend fun run(packages: List<String>): BoostProgress? = mutex.withLock {
        if (packages.isEmpty()) return null
        cancel = false
        AutomationHolder.cancelRequested = false
        val method = bestMethod()
        val pm = context.packageManager
        var items = packages.distinct().map { p ->
            BoostItem(p, runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrDefault(p))
        }
        var state = BoostProgress(method, items, availMb())
        _progress.value = state
        val service = AutomationHolder.service
        if (method == StopMethod.AccessibilityForceStop) service?.beginCover(items.size)

        for ((i, item) in items.withIndex()) {
            if (cancel || AutomationHolder.cancelRequested) { state = state.copy(cancelled = true); break }
            state = update(state, i, ItemStatus.Working)
            if (method == StopMethod.AccessibilityForceStop) service?.updateCover(i + 1, items.size, item.label)
            val status = try { stopOne(method, item.packageName) } catch (_: Exception) { ItemStatus.Failed }
            state = update(state, i, status)
        }
        if (method == StopMethod.AccessibilityForceStop) service?.endCover()
        delay(700) // let Android settle before measuring memory again
        state = state.copy(ramAfterMb = availMb(), finished = true)
        _progress.value = state
        state
    }

    private fun update(s: BoostProgress, index: Int, status: ItemStatus): BoostProgress {
        val next = s.copy(items = s.items.mapIndexed { i, it -> if (i == index) it.copy(status = status) else it })
        _progress.value = next
        return next
    }

    private suspend fun stopOne(method: StopMethod, pkg: String): ItemStatus = when (method) {
        StopMethod.ShizukuForceStop -> {
            val r = shell.forceStop(pkg)
            if (!r.ok) ItemStatus.Failed else if (isStopped(pkg)) ItemStatus.Stopped else ItemStatus.Requested
        }
        StopMethod.AccessibilityForceStop -> when (AutomationHolder.service?.forceStop(pkg)) {
            StopResult.Stopped -> ItemStatus.Stopped
            StopResult.NotRunning -> ItemStatus.NotRunning
            StopResult.NotConfirmed -> ItemStatus.Requested
            else -> ItemStatus.Failed
        }
        StopMethod.BackgroundKill -> {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pkg)
            ItemStatus.Requested // Android does not say whether anything was running
        }
    }

    private fun isStopped(pkg: String) = runCatching {
        context.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_STOPPED != 0
    }.getOrDefault(false)

    private fun availMb(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem / 1_048_576
    }
}
