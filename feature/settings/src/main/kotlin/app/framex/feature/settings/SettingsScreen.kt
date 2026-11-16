package app.framex.feature.settings

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.framex.core.common.ShizukuState
import app.framex.core.designsystem.label
import app.framex.core.designsystem.note
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.framex.core.common.ThemeMode
import app.framex.core.designsystem.FrameXCard

@Composable
fun SettingsScreen(contentPadding: PaddingValues, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            FrameXCard {
                Text(stringResource(R.string.set_theme), style = MaterialTheme.typography.titleMedium)
                Note(stringResource(R.string.set_theme_note))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { m ->
                        val name = stringResource(when (m) {
                            ThemeMode.System -> R.string.set_theme_system
                            ThemeMode.Light -> R.string.set_theme_light
                            ThemeMode.Dark -> R.string.set_theme_dark
                        })
                        FilterChip(selected = s.themeMode == m, onClick = { vm.setTheme(m) }, label = { Text(name) })
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            item { LanguageCard() }
        }
        item {
            FrameXCard {
                Text(stringResource(R.string.set_interval), style = MaterialTheme.typography.titleMedium)
                Note(stringResource(R.string.set_interval_note))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1000L, 2000L, 5000L).forEach { ms ->
                        FilterChip(selected = s.monitoringIntervalMs == ms, onClick = { vm.setInterval(ms) },
                            label = { Text(stringResource(R.string.set_seconds, (ms / 1000).toInt())) })
                    }
                }
            }
        }
        item { PlayCard(s.autoAnalyzeOnPlay, vm::setAutoAnalyze) }
        item { OverlayCard(s.fpsOverlayEnabled, vm::setOverlay) }
        item { ShizukuCard(vm) }
        item {
            FrameXCard {
                Text(stringResource(R.string.set_history), style = MaterialTheme.typography.titleMedium)
                Note(stringResource(R.string.set_history_note))
                OutlinedButton(onClick = vm::clearHistory) { Text(stringResource(R.string.set_history_clear)) }
            }
        }
        item {
            FrameXCard {
                Text(stringResource(R.string.set_permissions), style = MaterialTheme.typography.titleMedium)
                Note(stringResource(R.string.set_permissions_note))
            }
        }
    }
}

@Composable
private fun LanguageCard() {
    val ctx = LocalContext.current
    val lm = remember { ctx.getSystemService(Context.LOCALE_SERVICE) as LocaleManager }
    var tag by remember { mutableStateOf(lm.applicationLocales.toLanguageTags().substringBefore('-')) }
    FrameXCard {
        Text(stringResource(R.string.set_language), style = MaterialTheme.typography.titleMedium)
        Note(stringResource(R.string.set_language_note))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("" to stringResource(R.string.set_lang_system), "en" to "English", "ru" to "Русский").forEach { (code, name) ->
                FilterChip(selected = tag == code, onClick = {
                    tag = code
                    lm.applicationLocales = if (code.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(code)
                }, label = { Text(name) })
            }
        }
    }
}

@Composable
private fun Note(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun ShizukuCard(vm: SettingsViewModel) {
    val st by vm.shizuku.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshShizuku() }
    FrameXCard {
        Text(stringResource(R.string.set_shizuku), style = MaterialTheme.typography.titleMedium)
        Text(st.state.label(), style = MaterialTheme.typography.titleLarge)
        Note(st.state.note())
        if (st.state == ShizukuState.Ready) {
            Note(stringResource(R.string.set_shizuku_details, st.serverVersion ?: 0, if (st.uid == 0) "root" else "shell",
                stringResource(if (st.serviceConnected) R.string.set_shizuku_service_on else R.string.set_shizuku_service_off)))
        }
        when (st.state) {
            ShizukuState.NeedsPermission, ShizukuState.PermissionDenied ->
                androidx.compose.material3.Button(onClick = vm::requestShizuku) { Text(stringResource(R.string.set_shizuku_request)) }
            ShizukuState.NotRunning, ShizukuState.Outdated ->
                OutlinedButton(onClick = {
                    ctx.packageManager.getLaunchIntentForPackage(app.framex.core.shizuku.ShizukuManager.SHIZUKU_PACKAGE)?.let { ctx.startActivity(it) }
                }) { Text(stringResource(R.string.set_shizuku_open)) }
            ShizukuState.NotInstalled ->
                OutlinedButton(onClick = {
                    ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/RikkaApps/Shizuku/releases")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text(stringResource(R.string.set_shizuku_download)) }
            ShizukuState.Ready -> {}
        }
    }
}

@Composable
private fun PlayCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
    FrameXCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.set_play_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = enabled, onCheckedChange = onChange)
        }
        Note(stringResource(R.string.set_play_note))
    }
}

@Composable
private fun OverlayCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = android.provider.Settings.canDrawOverlays(ctx) }
    FrameXCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.set_overlay_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = enabled && granted, enabled = granted, onCheckedChange = onChange)
        }
        Note(stringResource(R.string.set_overlay_note))
        if (!granted) {
            Note(stringResource(R.string.set_overlay_need))
            OutlinedButton(onClick = {
                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${ctx.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text(stringResource(R.string.set_overlay_grant)) }
        }
    }
}
