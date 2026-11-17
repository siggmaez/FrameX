package app.framex.core.optimization

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
 *
 * With shell access the stops run in parallel, so a slow app (a big game) no longer holds up the others.
 * The visible Settings automation (accessibility) has to go one app at a time.
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

    private fun setStatus(index: Int, status: ItemStatus) {
        _progress.update { p -> p?.copy(items = p.items.mapIndexed { i, it -> if (i == index) it.copy(status = status) else it }) }
    }

    suspend fun run(packages: List<String>): BoostProgress? = mutex.withLock {
        if (packages.isEmpty()) return null
        cancel = false
        AutomationHolder.cancelRequested = false
        shell.ensureReady() // bring the shell link back first if it was paired before
        val method = bestMethod()
        val pm = context.packageManager
        val items = packages.distinct().map { p ->
            BoostItem(p, runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrDefault(p))
        }
        _progress.value = BoostProgress(method, items, availMb())

        if (method == StopMethod.AccessibilityForceStop) {
            val service = AutomationHolder.service
            service?.beginCover(items.size)
            for ((i, item) in items.withIndex()) {
                if (cancel || AutomationHolder.cancelRequested) break
                setStatus(i, ItemStatus.Working)
                service?.updateCover(i + 1, items.size, item.label)
                setStatus(i, try { stopOne(method, item.packageName) } catch (_: Exception) { ItemStatus.Failed })
            }
            service?.endCover()
        } else {
            val gate = Semaphore(PARALLEL)
            coroutineScope {
                items.indices.map { i ->
                    async {
                        gate.withPermit {
                            if (cancel) return@withPermit
                            setStatus(i, ItemStatus.Working)
                            setStatus(i, try { stopOne(method, items[i].packageName) } catch (_: Exception) { ItemStatus.Failed })
                        }
                    }
                }.awaitAll()
            }
        }
        val wasCancelled = cancel || AutomationHolder.cancelRequested
        delay(400) // let Android settle before measuring memory again
        _progress.update { it?.copy(ramAfterMb = availMb(), finished = true, cancelled = wasCancelled) }
        _progress.value
    }

    private suspend fun stopOne(method: StopMethod, pkg: String): ItemStatus = when (method) {
        StopMethod.ShizukuForceStop -> {
            val r = shell.forceStop(pkg)
            // a slow app can outlast the command's timeout yet still die, so the flag decides, not the exit code
            when {
                waitStopped(pkg) -> ItemStatus.Stopped
                r.ok -> ItemStatus.Requested
                else -> ItemStatus.Failed
            }
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

    /** The "stopped" flag appears a moment after the process dies; heavy games need a little longer. */
    private suspend fun waitStopped(pkg: String): Boolean {
        repeat(STOP_POLLS) {
            if (isStopped(pkg)) return true
            delay(STOP_POLL_MS)
        }
        return false
    }

    private fun isStopped(pkg: String) = runCatching {
        context.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_STOPPED != 0
    }.getOrDefault(false)

    private fun availMb(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem / 1_048_576
    }

    private companion object {
        const val PARALLEL = 6
        const val STOP_POLLS = 10
        const val STOP_POLL_MS = 250L
    }
}
