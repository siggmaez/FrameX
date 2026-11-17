package app.framex.core.common

/** A measurement that is either really available or honestly unavailable. Never a fabricated value. */
sealed interface Metric<out T> {
    data class Available<T>(val value: T) : Metric<T>
    data class Unavailable(val reason: String) : Metric<Nothing>
}

enum class ThemeMode { System, Light, Dark }

enum class OptimizationProfile { Balanced, Gaming, Performance, BatterySaver, Custom }

/** Individual optimization steps. Profiles are sets of these. Each one is only applied if the device supports it. */
enum class OptAction { DoNotDisturb, StopBackgroundApps, LowLatencyWifi, ReduceBrightness, GameModePerformance, GameModeBattery, BatterySaver, RenderScale, FixedPerformance, RefreshLock, WifiLowLatencyMode, BackgroundDataLimit }

/** Everything FrameX can know about Shizuku. Only Ready allows privileged operations. */
enum class ShizukuState { NotInstalled, NotRunning, Outdated, NeedsPermission, PermissionDenied, Ready }

enum class ConnectionKind { Wifi, Mobile }

/**
 * Where FrameX may use the network. Only the diagnostics you start yourself ever use it.
 * Local = FrameX makes no network connection at all.
 */
enum class NetworkMode {
    Local, WifiOnly, MobileOnly, WifiAndMobile;

    val allowed: List<ConnectionKind>
        get() = when (this) {
            Local -> emptyList()
            WifiOnly -> listOf(ConnectionKind.Wifi)
            MobileOnly -> listOf(ConnectionKind.Mobile)
            WifiAndMobile -> listOf(ConnectionKind.Wifi, ConnectionKind.Mobile)
        }

    fun allows(kind: ConnectionKind) = kind in allowed
}

/** Look of the in-game overlay. */
enum class OverlayStyle { Stealth, Minimal, Micro, Vengeance, Katana, Raven, Aurora, Neon, Retro, Glass, Sunset, Carbon, Frost }
