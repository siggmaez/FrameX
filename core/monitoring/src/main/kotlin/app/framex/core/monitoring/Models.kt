package app.framex.core.monitoring

import app.framex.core.common.Metric

data class RamInfo(val totalBytes: Long, val availableBytes: Long, val lowMemory: Boolean) {
    val usedBytes: Long get() = (totalBytes - availableBytes).coerceAtLeast(0)
    val usedFraction: Float get() = if (totalBytes > 0) usedBytes.toFloat() / totalBytes else 0f
}

data class BatteryInfo(val temperatureC: Float, val levelPercent: Int?, val charging: Boolean)

data class DisplayRefresh(val currentHz: Float, val supportedHz: List<Float>)


data class SystemSnapshot(
    val ram: Metric<RamInfo>,
    val cpuLoad: Metric<Float>,
    val battery: Metric<BatteryInfo>,
    val display: Metric<DisplayRefresh>,
)
