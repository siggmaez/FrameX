package app.framex.core.common

/** A measurement that is either really available or honestly unavailable. Never a fabricated value. */
sealed interface Metric<out T> {
    data class Available<T>(val value: T) : Metric<T>
    data class Unavailable(val reason: String) : Metric<Nothing>
}

enum class ThemeMode { System, Light, Dark }

enum class OptimizationProfile { Balanced, Gaming, Performance, BatterySaver, Custom }

/** Individual optimization steps. Profiles are sets of these. Each one is only applied if the device supports it. */
enum class OptAction { DoNotDisturb, StopBackgroundApps, GameModePerformance, GameModeBattery, BatterySaver }
