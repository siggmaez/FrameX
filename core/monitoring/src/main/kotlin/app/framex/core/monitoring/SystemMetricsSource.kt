package app.framex.core.monitoring

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.view.Display
import app.framex.core.common.Metric
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Reads real system metrics. Polls only while collected, so it stops when the UI is not visible. */
@Singleton
class SystemMetricsSource @Inject constructor(@ApplicationContext private val context: Context) {

    @OptIn(ExperimentalCoroutinesApi::class)
    fun snapshots(intervalMs: Flow<Long>): Flow<SystemSnapshot> =
        intervalMs.distinctUntilChanged().flatMapLatest { interval ->
            flow {
                val cpu = CpuSampler(context.getString(R.string.mon_cpu_restricted), context.getString(R.string.mon_cpu_first_sample))
                while (true) {
                    emit(SystemSnapshot(readRam(), cpu.sample(), readBattery(), readDisplay()))
                    delay(interval)
                }
            }
        }.flowOn(Dispatchers.Default)

    private class CpuSampler(restricted: String, private val firstSample: String) {
        private val restrictedMetric = Metric.Unavailable(restricted)
        private val calc = CpuLoadCalculator()
        private var blocked = false

        fun sample(): Metric<Float> {
            if (blocked) return restrictedMetric
            val line = readProcStatLine()
            if (line == null) {
                blocked = true
                return restrictedMetric
            }
            return calc.update(line)?.let { Metric.Available(it) } ?: Metric.Unavailable(firstSample)
        }

        private fun readProcStatLine(): String? = try {
            File("/proc/stat").bufferedReader().use { it.readLine() }?.takeIf { it.startsWith("cpu") }
        } catch (_: Exception) {
            null
        }
    }

    private fun readRam(): Metric<RamInfo> = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        Metric.Available(RamInfo(mi.totalMem, mi.availMem, mi.lowMemory))
    } catch (e: Exception) {
        Metric.Unavailable(context.getString(R.string.mon_ram_error))
    }

    private fun readBattery(): Metric<BatteryInfo> {
        val i: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return Metric.Unavailable(context.getString(R.string.mon_battery_not_reported))
        val tenths = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (tenths == Int.MIN_VALUE) return Metric.Unavailable(context.getString(R.string.mon_battery_temp_missing))
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else null
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return Metric.Available(BatteryInfo(tenths / 10f, pct, charging))
    }

    private fun readDisplay(): Metric<DisplayRefresh> = try {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val rates = d.supportedModes.map { it.refreshRate }.distinctBy { Math.round(it) }.sorted()
        Metric.Available(DisplayRefresh(d.refreshRate, rates))
    } catch (e: Exception) {
        Metric.Unavailable(context.getString(R.string.mon_display_error))
    }


}
