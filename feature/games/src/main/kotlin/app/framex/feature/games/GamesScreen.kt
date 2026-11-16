package app.framex.feature.games

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.contract.ActivityResultContracts
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
        value = withContext(Dispatchers.IO) { runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
    }
    icon?.let { Image(it, null, Modifier.size(40.dp)) } ?: androidx.compose.foundation.layout.Spacer(Modifier.size(40.dp))
}
