package app.framex.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.framex.core.common.OptimizationProfile

@Composable
fun OptimizationProfile.label(): String = stringResource(
    when (this) {
        OptimizationProfile.Balanced -> R.string.profile_balanced
        OptimizationProfile.Gaming -> R.string.profile_gaming
        OptimizationProfile.Performance -> R.string.profile_performance
        OptimizationProfile.BatterySaver -> R.string.profile_battery_saver
        OptimizationProfile.Custom -> R.string.profile_custom
    }
)
