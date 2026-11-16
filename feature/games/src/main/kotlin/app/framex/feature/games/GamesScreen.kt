package app.framex.feature.games

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.framex.core.common.OptimizationProfile
import app.framex.core.database.GameSessionEntity
import app.framex.core.designsystem.FrameXCard
import app.framex.core.designsystem.label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GamesScreen(contentPadding: PaddingValues, vm: GamesViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val autoAnalyze by vm.autoAnalyze.collectAsStateWithLifecycle()
    PlayDialogs(ui, vm)
    val appCtx = LocalContext.current
    val captureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r -> vm.onCaptureResult(r.resultCode, r.data) }
    androidx.compose.runtime.LaunchedEffect(ui.captureFor) {
        if (ui.captureFor != null) {
            val mpm = appCtx.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            captureLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }
    val ctx = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshAccess() }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.setWatching(true) }
    val trackedPkgs = ui.tracked.map { it.packageName }.toSet()
    val candidates = ui.installed.orEmpty().filter { it.packageName !in trackedPkgs && (ui.showAll || it.isGame) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp, contentPadding.calculateTopPadding() + 16.dp, 16.dp, contentPadding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            FrameXCard {
                Text(stringResource(R.string.g_detect_title), style = MaterialTheme.typography.titleMedium)
                Note(stringResource(R.string.g_detect_note))
                if (!ui.hasUsageAccess) {
                    Note(stringResource(R.string.g_need_usage))
                    OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                        Text(stringResource(R.string.g_grant_usage))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.g_detect_switch), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = ui.watching, enabled = ui.hasUsageAccess, onCheckedChange = { on ->
                        if (on && Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.setWatching(on)
                    })
                }
                Note(stringResource(R.string.g_auto_note))
            }
        }
        ui.notice?.let { n ->
            item {
                FrameXCard {
                    Text(stringResource(if (n == PlayNotice.NoUsageAccess) R.string.g_notice_usage else R.string.g_notice_launch), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = vm::dismissNotice) { Text(stringResource(R.string.g_ok)) }
                }
            }
        }
        item { Text(stringResource(R.string.g_my_games), style = MaterialTheme.typography.titleMedium) }
        if (ui.tracked.isEmpty()) item { Note(stringResource(R.string.g_none)) }
        items(ui.tracked.size) { idx ->
            val g = ui.tracked[idx]
            FrameXCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppIcon(g.packageName)
                    Text(g.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.remove(g.packageName) }) { Text(stringResource(R.string.g_remove)) }
                }
                androidx.compose.material3.Button(onClick = { vm.play(g) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.g_play))
                }
                Note(stringResource(if (autoAnalyze) R.string.g_play_note_auto else R.string.g_play_note_saved))
                if (g.stopPackages.isNotBlank()) {
                    TextButton(onClick = { vm.forgetStopList(g) }) { Text(stringResource(R.string.g_forget_stop)) }
                }
                Note(stringResource(R.string.g_profile))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OptimizationProfile.entries.forEach { p ->
                        FilterChip(selected = g.profile == p.name, onClick = { vm.setProfile(g, p) }, label = { Text(p.label()) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.g_auto_apply), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = g.autoApply, onCheckedChange = { vm.setAuto(g, it) })
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.g_installed), style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.g_show_all), style = MaterialTheme.typography.bodySmall)
                    Switch(checked = ui.showAll, onCheckedChange = vm::setShowAll)
                }
            }
        }
        if (ui.installed == null) item { Note(stringResource(R.string.g_loading)) }
        else if (candidates.isEmpty()) item { Note(stringResource(R.string.g_no_found)) }
        items(candidates.size) { idx ->
            val a = candidates[idx]
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                AppIcon(a.packageName)
                Text(a.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { vm.add(a) }) { Text(stringResource(R.string.g_add)) }
            }
        }
        item { Text(stringResource(R.string.g_sessions), style = MaterialTheme.typography.titleMedium) }
        if (ui.sessions.isEmpty()) item { Note(stringResource(R.string.g_sessions_empty)) }
        items(ui.sessions.size) { idx -> SessionCard(ui.sessions[idx]) }
        if (ui.sessions.isNotEmpty()) item { OutlinedButton(onClick = vm::clearSessions) { Text(stringResource(R.string.g_clear)) } }
    }
}

@Composable
fun SessionCard(s: GameSessionEntity) {
    val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.startMs))
    FrameXCard {
        Text(stringResource(R.string.g_session_line, s.label, date), style = MaterialTheme.typography.titleSmall)
        val dur = if (s.endMs == null) stringResource(R.string.g_session_running) else duration(s.endMs!! - s.startMs)
        val prof = s.appliedProfile?.let { name -> OptimizationProfile.entries.firstOrNull { it.name == name }?.label() ?: name }
            ?: stringResource(R.string.g_no_profile_applied)
        Note(stringResource(R.string.g_session_detail, dur, prof))
        if (s.endMs != null || s.avgFps != null) {
            val fpsAvg = s.avgFps
            if (fpsAvg != null) {
                Note(stringResource(if (s.fpsSource == "ScreenCapture") R.string.g_session_fps_est else R.string.g_session_fps, String.format(Locale.US, "%.0f", fpsAvg), s.minFps?.let { String.format(Locale.US, "%.0f", it) } ?: "—", s.lowFps?.let { String.format(Locale.US, "%.0f", it) } ?: "—"))
            } else Note(stringResource(R.string.g_session_fps_na))
        }
        if (s.startTempC != null || s.maxTempC != null) {
            Note(stringResource(R.string.g_session_temp, s.startTempC?.let { String.format(Locale.US, "%.1f", it) } ?: "—", s.maxTempC?.let { String.format(Locale.US, "%.1f", it) } ?: "—"))
        }
    }
}

@Composable
private fun duration(ms: Long): String {
    val sec = (ms / 1000).toInt()
    return if (sec < 60) stringResource(R.string.g_seconds, sec) else stringResource(R.string.g_minutes, sec / 60)
}

@Composable
private fun Note(t: String) = Text(t, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun AppIcon(pkg: String) {
    val ctx = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, pkg) {
        val bmp = withContext(Dispatchers.IO) { runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
        value = bmp
    }
    icon?.let { Image(it, null, Modifier.size(40.dp)) } ?: androidx.compose.foundation.layout.Spacer(Modifier.size(40.dp))
}

@Composable
private fun PlayDialogs(ui: GamesUiState, vm: GamesViewModel) {
    when (val step = ui.play) {
        null -> {}
        is PlayStep.Advice -> androidx.compose.material3.AlertDialog(
            onDismissRequest = vm::cancelPlay,
            title = { Text(stringResource(R.string.g_advice_title, step.game.label)) },
            text = {
                androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Note(stringResource(R.string.g_advice_profile, step.plan.profile.label()))
                    step.plan.advisories.forEach { a ->
                        Text(stringResource(when (a) {
                            app.framex.core.optimization.Advisory.BatterySaverOn -> R.string.adv_saver
                            app.framex.core.optimization.Advisory.RefreshBelowMax -> R.string.adv_refresh
                            app.framex.core.optimization.Advisory.DeviceHot -> R.string.adv_hot
                            app.framex.core.optimization.Advisory.WarmWhileCharging -> R.string.adv_charging
                            app.framex.core.optimization.Advisory.HighMemory -> R.string.adv_memory
                        }), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (step.plan.advisories.contains(app.framex.core.optimization.Advisory.BatterySaverOn))
                        TextButton(onClick = vm::openBatterySaverSettings) { Text(stringResource(R.string.g_open_saver)) }
                    if (step.plan.advisories.contains(app.framex.core.optimization.Advisory.RefreshBelowMax))
                        TextButton(onClick = vm::openDisplaySettings) { Text(stringResource(R.string.g_open_display)) }
                }
            },
            confirmButton = { TextButton(onClick = vm::continueAfterAdvice) { Text(stringResource(R.string.g_play_anyway)) } },
            dismissButton = { TextButton(onClick = vm::cancelPlay) { Text(stringResource(R.string.g_cancel)) } },
        )
        is PlayStep.ConfirmStop -> {
            val selected = androidx.compose.runtime.remember(step) { androidx.compose.runtime.mutableStateListOf<String>() }
            var remember by androidx.compose.runtime.remember(step) { androidx.compose.runtime.mutableStateOf(false) }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = vm::cancelPlay,
                title = { Text(stringResource(R.string.g_stop_title)) },
                text = {
                    androidx.compose.foundation.layout.Column {
                        Note(stringResource(R.string.g_stop_text, step.profile.label()))
                        if (step.apps.isEmpty()) Note(stringResource(R.string.g_stop_none))
                        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f, fill = false)) {
                            items(step.apps.size) { i ->
                                val a = step.apps[i]
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.Checkbox(checked = a.packageName in selected, onCheckedChange = { on -> if (on) selected += a.packageName else selected -= a.packageName })
                                    Text(a.label, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(checked = remember, onCheckedChange = { remember = it })
                            Text(stringResource(R.string.g_stop_remember), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { vm.confirmStop(selected.toList(), remember) }) { Text(stringResource(R.string.g_play)) } },
                dismissButton = { TextButton(onClick = vm::cancelPlay) { Text(stringResource(R.string.g_cancel)) } },
            )
        }
    }
}
