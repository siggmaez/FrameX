package app.framex.core.optimization

import app.framex.core.common.OptAction
import app.framex.core.common.OptimizationProfile

enum class UnsupportedReason { NeedsPolicyAccess, NeedsShizuku, NeedsAndroid12, NeedsAndroid13, NeedsTargetGame, NeedsWriteSettings, NoWifi, Unknown }

sealed interface Capability {
    data object Supported : Capability
    data class Unsupported(val reason: UnsupportedReason) : Capability
}

enum class GameModeLevel(val cmdName: String) { Performance("performance"), Battery("battery"), Standard("standard") }

data class ApplyRequest(
    /** Game the profile is applied for (needed by game-mode actions). */
    val targetPackage: String? = null,
    /** Packages the user confirmed to stop. */
    val stopPackages: List<String> = emptyList(),
    val customActions: Set<OptAction> = emptySet(),
    /** Added on top of the profile's own actions (used by game boosts). */
    val extraActions: Set<OptAction> = emptySet(),
    /** Render resolution factor for [OptAction.RenderScale], e.g. 0.85. */
    val renderScale: Float? = null,
    /** Refresh rate for [OptAction.RefreshLock]; null = the highest the screen supports. */
    val refreshHz: Int? = null,
    /** Whole-screen resolution factor for [OptAction.ScreenResolution], e.g. 0.75 (default when null). */
    val screenScale: Float? = null,
)

enum class ResultStatus { Applied, Restored, Failed, Skipped }

data class ExecResult(val status: ResultStatus, val message: String, val token: String? = null)

data class ActionResult(
    val action: OptAction,
    val status: ResultStatus,
    val message: String,
    val reason: UnsupportedReason? = null,
)

data class EngineReport(val profile: OptimizationProfile, val results: List<ActionResult>) {
    val anyApplied: Boolean get() = results.any { it.status == ResultStatus.Applied }
}

/** Pure mapping of profiles to the actions they consist of. */
object Profiles {
    fun actionsFor(profile: OptimizationProfile, custom: Set<OptAction>): Set<OptAction> = when (profile) {
        OptimizationProfile.Balanced -> emptySet()
        OptimizationProfile.Gaming -> setOf(OptAction.DoNotDisturb, OptAction.StopBackgroundApps, OptAction.LowLatencyWifi)
        OptimizationProfile.Performance -> setOf(OptAction.DoNotDisturb, OptAction.StopBackgroundApps, OptAction.LowLatencyWifi, OptAction.GameModePerformance)
        OptimizationProfile.BatterySaver -> setOf(OptAction.BatterySaver, OptAction.GameModeBattery, OptAction.ReduceBrightness)
        OptimizationProfile.Custom -> custom
    }
}

/** Decides what the device can really do. Never assumes support. */
object CapabilityEvaluator {
    fun evaluate(
        policyAccess: Boolean, shellAvailable: Boolean, sdk: Int,
        canWriteSettings: Boolean = false, wifiSupported: Boolean = true,
    ): Map<OptAction, Capability> = mapOf(
        OptAction.DoNotDisturb to if (policyAccess) Capability.Supported else Capability.Unsupported(UnsupportedReason.NeedsPolicyAccess),
        OptAction.StopBackgroundApps to Capability.Supported,
        OptAction.LowLatencyWifi to if (wifiSupported) Capability.Supported else Capability.Unsupported(UnsupportedReason.NoWifi),
        OptAction.ReduceBrightness to if (canWriteSettings) Capability.Supported else Capability.Unsupported(UnsupportedReason.NeedsWriteSettings),
        OptAction.GameModePerformance to game(shellAvailable, sdk),
        OptAction.GameModeBattery to game(shellAvailable, sdk),
        OptAction.BatterySaver to if (shellAvailable) Capability.Supported else Capability.Unsupported(UnsupportedReason.NeedsShizuku),
        OptAction.RenderScale to when {
            sdk < 33 -> Capability.Unsupported(UnsupportedReason.NeedsAndroid13)
            !shellAvailable -> Capability.Unsupported(UnsupportedReason.NeedsShizuku)
            else -> Capability.Supported
        },
        OptAction.FixedPerformance to when {
            sdk < 31 -> Capability.Unsupported(UnsupportedReason.NeedsAndroid12)
            !shellAvailable -> Capability.Unsupported(UnsupportedReason.NeedsShizuku)
            else -> Capability.Supported
        },
        OptAction.RefreshLock to if (shellAvailable) Capability.Supported else Capability.Unsupported(UnsupportedReason.NeedsShizuku),
        OptAction.WifiLowLatencyMode to if (shellAvailable) Capability.Supported else Capability.Unsupported(UnsupportedReason.NeedsShizuku),
        OptAction.BackgroundDataLimit to if (shellAvailable) Capability.Supported else Capability.Unsupported(UnsupportedReason.NeedsShizuku),
        OptAction.ScreenResolution to if (shellAvailable) Capability.Supported else Capability.Unsupported(UnsupportedReason.NeedsShizuku),
    )

    private fun game(shell: Boolean, sdk: Int): Capability = when {
        sdk < 31 -> Capability.Unsupported(UnsupportedReason.NeedsAndroid12)
        !shell -> Capability.Unsupported(UnsupportedReason.NeedsShizuku)
        else -> Capability.Supported
    }
}

enum class SmartReason { TooHot, HighMemory, PrivilegedAvailable, Default }
data class SmartRecommendation(val profile: OptimizationProfile, val reason: SmartReason)

object SmartBoost {
    /** Rule-based and deterministic; the reason is always shown to the user. */
    fun recommend(batteryTempC: Float?, ramUsedFraction: Float?, privileged: Boolean): SmartRecommendation = when {
        batteryTempC != null && batteryTempC >= 45f -> SmartRecommendation(OptimizationProfile.Balanced, SmartReason.TooHot)
        ramUsedFraction != null && ramUsedFraction >= 0.85f -> SmartRecommendation(OptimizationProfile.Gaming, SmartReason.HighMemory)
        privileged -> SmartRecommendation(OptimizationProfile.Performance, SmartReason.PrivilegedAvailable)
        else -> SmartRecommendation(OptimizationProfile.Gaming, SmartReason.Default)
    }
}

/** What the device looks like right now, as read from Android. Null means the value could not be read. */
data class Findings(
    val batteryTempC: Float?,
    val charging: Boolean,
    val ramUsedFraction: Float?,
    val batterySaverOn: Boolean,
    val refreshHz: Float?,
    val maxRefreshHz: Float?,
    val privileged: Boolean,
)

/** Things FrameX can see but cannot change without privileges; the user decides. */
enum class Advisory(val important: Boolean) {
    BatterySaverOn(true), RefreshBelowMax(true), DeviceHot(true), WarmWhileCharging(false), HighMemory(false)
}

data class LaunchPlan(val profile: OptimizationProfile, val advisories: List<Advisory>)

/** Rule-based, deterministic and explainable: the same findings always give the same plan. */
object GamePlanner {
    const val HOT_C = 45f
    const val WARM_C = 40f
    const val HIGH_RAM = 0.85f

    fun plan(f: Findings): LaunchPlan {
        val hot = f.batteryTempC != null && f.batteryTempC >= HOT_C
        val highRam = f.ramUsedFraction != null && f.ramUsedFraction >= HIGH_RAM
        val profile = when {
            hot -> OptimizationProfile.BatterySaver // less heat: dimmer screen, lower load
            highRam -> OptimizationProfile.Gaming
            f.privileged -> OptimizationProfile.Performance
            else -> OptimizationProfile.Gaming
        }
        val adv = buildList {
            if (f.batterySaverOn) add(Advisory.BatterySaverOn)
            if (f.refreshHz != null && f.maxRefreshHz != null && f.refreshHz < f.maxRefreshHz - 1f) add(Advisory.RefreshBelowMax)
            if (hot) add(Advisory.DeviceHot)
            else if (f.charging && f.batteryTempC != null && f.batteryTempC >= WARM_C) add(Advisory.WarmWhileCharging)
            if (highRam) add(Advisory.HighMemory)
        }
        return LaunchPlan(profile, adv)
    }
}

/** The screen's own size and any override currently set (what `wm size` reports). */
data class WmSize(val physical: Pair<Int, Int>, val override: Pair<Int, Int>?) {
    val current: Pair<Int, Int> get() = override ?: physical
}

object ScreenScaling {
    private const val STEP = 8
    private const val MIN_SIDE = 480

    /** [scale] of the PHYSICAL size, in multiples of 8 pixels; null if that would not be smaller or is too small. */
    fun target(physical: Pair<Int, Int>, scale: Float): Pair<Int, Int>? {
        val s = scale.coerceIn(0.5f, 0.95f)
        val w = Math.round(physical.first * s / STEP) * STEP
        val h = Math.round(physical.second * s / STEP) * STEP
        return if (w >= physical.first || h >= physical.second || minOf(w, h) < MIN_SIDE) null else w to h
    }
}
