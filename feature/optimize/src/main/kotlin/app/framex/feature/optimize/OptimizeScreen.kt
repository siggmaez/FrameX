package app.framex.feature.optimize

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.framex.core.common.OptAction
import app.framex.core.common.OptimizationProfile
import app.framex.core.designsystem.FrameXCard
import app.framex.core.designsystem.label
import app.framex.core.optimization.ActionResult
import app.framex.core.optimization.Capability
import app.framex.core.optimization.Profiles
import app.framex.core.optimization.ResultStatus
import app.framex.core.optimization.SmartReason
import app.framex.core.optimization.UnsupportedReason
import java.text.DateFormat
import java.util.Date

@Composable
private fun OptAction.title() = stringResource(when (this) {
    OptAction.DoNotDisturb -> R.string.act_dnd
    OptAction.StopBackgroundApps -> R.string.act_kill
    OptAction.GameModePerformance -> R.string.act_gm_perf
    OptAction.GameModeBattery -> R.string.act_gm_batt
    OptAction.BatterySaver -> R.string.act_saver
})

@Composable
private fun UnsupportedReason?.text() = stringResource(when (this) {
    UnsupportedReason.NeedsPolicyAccess -> R.string.reason_policy
    UnsupportedReason.NeedsShizuku -> R.string.reason_shizuku
    UnsupportedReason.NeedsAndroid12 -> R.string.reason_a12
    UnsupportedReason.NeedsTargetGame -> R.string.reason_target
    UnsupportedReason.Unknown -> R.string.reason_unknown
    null -> R.string.reason_none_selected
})

@Composable
private fun ResultStatus.text() = stringResource(when (this) {
    ResultStatus.Applied -> R.string.st_applied
    ResultStatus.Restored -> R.string.st_restored
    ResultStatus.Failed -> R.string.st_failed
    ResultStatus.Skipped -> R.string.st_skipped
})

private fun fmt(ts: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ts))

@Composable
fun OptimizeScreen(contentPadding: PaddingValues, vm: OptimizeViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshCapabilities() }
    BoostDialogs(vm, ui)
    val actions = Profiles.actionsFor(ui.selected, ui.customActions)
    LazyColumn(
        contentPadding = PaddingValues(16.dp, contentPadding.calculateTopPadding() + 16.dp, 16.dp, contentPadding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            FrameXCard {
                Text(stringResource(R.string.opt_title_active), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val a = ui.active
                if (a == null) Text(stringResource(R.string.opt_none_applied), style = MaterialTheme.typography.titleLarge)
                else {
                    Text(a.profile.label(), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.opt_applied_at, fmt(a.appliedAtMs)), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = vm::restore, enabled = !ui.busy) { Text(stringResource(R.string.opt_restore)) }
                }
            }
        }
        item {
            FrameXCard {
                Text(stringResource(R.string.opt_profiles), style = MaterialTheme.typography.titleMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OptimizationProfile.entries.forEach { p ->
                        FilterChip(selected = ui.selected == p, onClick = { vm.select(p) }, label = { Text(p.label()) })
                    }
                }
                if (ui.selected == OptimizationProfile.Balanced) Text(stringResource(R.string.opt_balanced_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.requestApply(ui.selected) }, enabled = !ui.busy) {
                        Text(stringResource(if (ui.busy) R.string.opt_working else R.string.opt_apply))
                    }
                    OutlinedButton(onClick = vm::smartBoost, enabled = !ui.busy) { Text(stringResource(R.string.opt_smart)) }
                }
                Text(stringResource(R.string.opt_no_fps_promise), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            FrameXCard {
                Text(stringResource(R.string.opt_what_it_does), style = MaterialTheme.typography.titleMedium)
                val shown = if (ui.selected == OptimizationProfile.Custom) OptAction.entries else actions.toList()
                shown.forEach { a ->
                    val cap = ui.capabilities[a]
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        if (ui.selected == OptimizationProfile.Custom) Checkbox(checked = a in ui.customActions, onCheckedChange = { vm.toggleCustom(a) })
                        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                            Text(a.title(), style = MaterialTheme.typography.bodyLarge)
                            when (cap) {
                                Capability.Supported -> Text(stringResource(R.string.opt_available), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                is Capability.Unsupported -> Text(cap.reason.text(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                null -> {}
                            }
                        }
                        if (cap is Capability.Unsupported && cap.reason == UnsupportedReason.NeedsPolicyAccess) {
                            TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                                Text(stringResource(R.string.opt_grant))
                            }
                        }
                    }
                }
            }
        }
        ui.report?.let { rep ->
            item {
                FrameXCard {
                    Text(stringResource(R.string.opt_result), style = MaterialTheme.typography.titleMedium)
                    rep.results.forEach { ResultRow(it) }
                }
            }
        }
        item {
            FrameXCard {
                Text(stringResource(R.string.opt_history), style = MaterialTheme.typography.titleMedium)
                if (ui.log.isEmpty()) Text(stringResource(R.string.opt_history_empty), style = MaterialTheme.typography.bodySmall)
                ui.log.forEach { e ->
                    Text("${fmt(e.timestamp)} · ${e.action} · ${e.status}${if (e.message.isNotEmpty()) " · ${e.message}" else ""}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ResultRow(r: ActionResult) {
    androidx.compose.foundation.layout.Column {
        Text("${r.action.title()} — ${r.status.text()}", style = MaterialTheme.typography.bodyMedium,
            color = if (r.status == ResultStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        val detail = if (r.status == ResultStatus.Skipped) r.reason.text() else r.message
        if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Quick Boost entry for the Home screen. */
@Composable
fun QuickBoostCard(vm: OptimizeViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshCapabilities() }
    BoostDialogs(vm, ui)
    FrameXCard {
        Text(stringResource(R.string.opt_quick), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.opt_quick_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = vm::smartBoost, enabled = !ui.busy) { Text(stringResource(if (ui.busy) R.string.opt_working else R.string.opt_quick)) }
        ui.report?.results?.forEach { ResultRow(it) }
    }
}

@Composable
private fun BoostDialogs(vm: OptimizeViewModel, ui: OptimizeUiState) {
    ui.smart?.let { rec ->
        AlertDialog(
            onDismissRequest = vm::cancelDialogs,
            title = { Text(stringResource(R.string.opt_smart_title, rec.profile.label())) },
            text = {
                Text(stringResource(when (rec.reason) {
                    SmartReason.TooHot -> R.string.smart_hot
                    SmartReason.HighMemory -> R.string.smart_memory
                    SmartReason.PrivilegedAvailable -> R.string.smart_privileged
                    SmartReason.Default -> R.string.smart_default
                }))
            },
            confirmButton = { TextButton(onClick = vm::acceptSmart) { Text(stringResource(R.string.opt_confirm)) } },
            dismissButton = { TextButton(onClick = vm::cancelDialogs) { Text(stringResource(R.string.opt_cancel)) } },
        )
    }
    ui.confirm?.let { c ->
        val selected: SnapshotStateList<String> = remember(c) { mutableStateListOf<String>() }
        AlertDialog(
            onDismissRequest = vm::cancelDialogs,
            title = { Text(stringResource(R.string.opt_stop_title)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(stringResource(R.string.opt_stop_text), style = MaterialTheme.typography.bodySmall)
                    if (c.apps.isEmpty()) Text(stringResource(R.string.opt_stop_none))
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(c.apps, key = { it.packageName }) { app ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = app.packageName in selected, onCheckedChange = { on -> if (on) selected += app.packageName else selected -= app.packageName })
                                Text(app.label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.confirmStop(selected.toList()) }) { Text(stringResource(R.string.opt_confirm)) } },
            dismissButton = { TextButton(onClick = vm::cancelDialogs) { Text(stringResource(R.string.opt_cancel)) } },
        )
    }
}
