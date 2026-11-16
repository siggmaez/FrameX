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

@Composable
fun app.framex.core.common.ShizukuState.label(): String = stringResource(when (this) {
    app.framex.core.common.ShizukuState.NotInstalled -> R.string.shz_NotInstalled
    app.framex.core.common.ShizukuState.NotRunning -> R.string.shz_NotRunning
    app.framex.core.common.ShizukuState.Outdated -> R.string.shz_Outdated
    app.framex.core.common.ShizukuState.NeedsPermission -> R.string.shz_NeedsPermission
    app.framex.core.common.ShizukuState.PermissionDenied -> R.string.shz_PermissionDenied
    app.framex.core.common.ShizukuState.Ready -> R.string.shz_Ready
})

@Composable
fun app.framex.core.common.ShizukuState.note(): String = stringResource(when (this) {
    app.framex.core.common.ShizukuState.NotInstalled -> R.string.shz_note_NotInstalled
    app.framex.core.common.ShizukuState.NotRunning -> R.string.shz_note_NotRunning
    app.framex.core.common.ShizukuState.Outdated -> R.string.shz_note_Outdated
    app.framex.core.common.ShizukuState.NeedsPermission -> R.string.shz_note_NeedsPermission
    app.framex.core.common.ShizukuState.PermissionDenied -> R.string.shz_note_PermissionDenied
    app.framex.core.common.ShizukuState.Ready -> R.string.shz_note_Ready
})
