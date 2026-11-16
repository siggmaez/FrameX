package app.framex.core.monitoring

import app.framex.core.common.Metric

enum class ThermalLevel {
    None, Light, Moderate, Severe, Critical, Emergency, Shutdown;

    companion object {
        /** Maps PowerManager.THERMAL_STATUS_* (0..6) or null when unknown. */
        fun fromStatus(status: Int): ThermalLevel? = entries.getOrNull(status)
    }
}

data class ThermalSensor(val name: String, val tempC: Float)

data class CoreFreq(val core: Int, val curKHz: Long?, val maxKHz: Long?)

data class CpuInfo(val cores: Int, val frequencies: Metric<List<CoreFreq>>)

data class DetailedSnapshot(
    val thermalLevel: Metric<ThermalLevel>,
    /** 1.0 = at the throttling threshold, lower is cooler. */
    val thermalHeadroom: Metric<Float>,
    val thermalSensors: Metric<List<ThermalSensor>>,
    val cpu: CpuInfo,
    val ownProcessPssKb: Metric<Long>,
)

enum class BatteryTempState { Normal, Warm, Hot }

/** FrameX guideline thresholds (not hardware limits). */
object Advisor {
    const val WARM_C = 40f
    const val HOT_C = 45f
    const val HIGH_RAM_FRACTION = 0.9f

    fun batteryTemp(tempC: Float): BatteryTempState = when {
        tempC >= HOT_C -> BatteryTempState.Hot
        tempC >= WARM_C -> BatteryTempState.Warm
        else -> BatteryTempState.Normal
    }

    fun highMemory(ram: RamInfo): Boolean = ram.lowMemory || ram.usedFraction >= HIGH_RAM_FRACTION
}
