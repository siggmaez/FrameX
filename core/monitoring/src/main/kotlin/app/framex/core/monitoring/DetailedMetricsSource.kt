package app.framex.core.monitoring

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import app.framex.core.common.Metric
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Heavier reads (sysfs, thermal API). Called only while the Monitor screen is visible. */
@Singleton
class DetailedMetricsSource @Inject constructor(@ApplicationContext private val context: Context) {

    suspend fun read(): DetailedSnapshot = withContext(Dispatchers.IO) {
        DetailedSnapshot(thermalLevel(), thermalHeadroom(), thermalSensors(), cpuInfo(), ownPss())
    }

    private fun thermalLevel(): Metric<ThermalLevel> = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        ThermalLevel.fromStatus(pm.currentThermalStatus)?.let { Metric.Available(it) }
            ?: Metric.Unavailable(context.getString(R.string.mon_thermal_unknown))
    } catch (e: Exception) { Metric.Unavailable(context.getString(R.string.mon_thermal_na)) }

    private fun thermalHeadroom(): Metric<Float> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Metric.Unavailable(context.getString(R.string.mon_headroom_android11))
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val h = pm.getThermalHeadroom(0)
            if (h.isNaN()) Metric.Unavailable(context.getString(R.string.mon_not_supported)) else Metric.Available(h)
        } catch (e: Exception) { Metric.Unavailable(context.getString(R.string.mon_not_supported)) }
    }

    private fun thermalSensors(): Metric<List<ThermalSensor>> {
        val zones = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
            ?: return Metric.Unavailable(context.getString(R.string.mon_sensors_list))
        val list = zones.sortedBy { it.name }.mapNotNull { z ->
            try {
                val raw = File(z, "temp").readText().trim().toFloatOrNull() ?: return@mapNotNull null
                val c = if (raw > 1000f || raw < -1000f) raw / 1000f else raw
                if (c !in -20f..150f) return@mapNotNull null
                ThermalSensor(File(z, "type").readText().trim(), c)
            } catch (_: Exception) { null }
        }
        return if (list.isEmpty()) Metric.Unavailable(context.getString(R.string.mon_sensors_unreadable))
        else Metric.Available(list)
    }

    private fun cpuInfo(): CpuInfo {
        val n = Runtime.getRuntime().availableProcessors()
        val freqs = (0 until n).map { i ->
            val base = "/sys/devices/system/cpu/cpu$i/cpufreq/"
            CoreFreq(i, readLong(base + "scaling_cur_freq"), readLong(base + "cpuinfo_max_freq"))
        }
        val any = freqs.any { it.curKHz != null || it.maxKHz != null }
        return CpuInfo(n, if (any) Metric.Available(freqs) else Metric.Unavailable(context.getString(R.string.mon_freq_unreadable)))
    }

    private fun readLong(path: String): Long? = try { File(path).readText().trim().toLongOrNull() } catch (_: Exception) { null }

    private fun ownPss(): Metric<Long> = try {
        val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        Metric.Available(mi.totalPss.toLong())
    } catch (e: Exception) { Metric.Unavailable(context.getString(R.string.mon_na)) }
}
