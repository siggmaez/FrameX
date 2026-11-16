package app.framex.core.optimization

import app.framex.core.common.OptAction
import app.framex.core.common.OptimizationProfile

enum class UnsupportedReason { NeedsPolicyAccess, NeedsShizuku, NeedsAndroid12, NeedsTargetGame, Unknown }

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
        OptimizationProfile.Gaming -> setOf(OptAction.DoNotDisturb, OptAction.StopBackgroundApps)
        OptimizationProfile.Performance -> setOf(OptAction.DoNotDisturb, OptAction.StopBackgroundApps, OptAction.GameModePerformance)
        OptimizationProfile.BatterySaver -> setOf(OptAction.BatterySaver, OptAction.GameModeBattery)
        OptimizationProfile.Custom -> custom
    }
}

/** Decides what the device can really do. Never assumes support. */
object CapabilityEvaluator {
    fun evaluate(policyAccess: Boolean, shellAvailable: Boolean, sdk: Int): Map<OptAction, Capability> = mapOf(
        OptAction.DoNotDisturb to if (policyAccess) Capability.Supported else Capability.Unsupported(UnsupportedReason.NeedsPolicyAccess),
        OptAction.StopBackgroundApps to Capability.Supported,
        OptAction.GameModePerformance to game(shellAvailable, sdk),
        OptAction.GameModeBattery to game(shellAvailable, sdk),
        OptAction.BatterySaver to if (shellAvailable) Capability.Supported else Capability.Unsupported(UnsupportedReason.NeedsShizuku),
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
