package app.framex.feature.settings

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        item { NetworkModeCard(s.networkMode, vm::setNetworkMode) }
        item { AutoStopCard(s.autoStopBackground, s.neverStopApps, vm) }
        item { PlayCard(s.autoAnalyzeOnPlay, vm::setAutoAnalyze) }
        item { OverlayCard(s.fpsOverlayEnabled, vm::setOverlay, s.fpsCaptureEstimate, vm::setCaptureEstimate, s.overlayStyle, vm::setOverlayStyle, s.overlayScope, vm::setOverlayScope, vm) }
        item { ShizukuCard(vm) }
        item { AdbCard(vm) }
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
@android.annotation.TargetApi(33)
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
private fun OverlayCard(enabled: Boolean, onChange: (Boolean) -> Unit, capture: Boolean, onCapture: (Boolean) -> Unit, style: app.framex.core.common.OverlayStyle, onStyle: (app.framex.core.common.OverlayStyle) -> Unit, scope: app.framex.core.common.OverlayScope, onScope: (app.framex.core.common.OverlayScope) -> Unit, vm: SettingsViewModel) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = android.provider.Settings.canDrawOverlays(ctx) }
    FrameXCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.set_overlay_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = enabled && granted, enabled = granted, onCheckedChange = onChange)
        }
        Note(stringResource(R.string.set_overlay_note))
        Text(stringResource(R.string.set_scope_title), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            app.framex.core.common.OverlayScope.entries.forEach { sc ->
                FilterChip(selected = sc == scope, onClick = { onScope(sc) }, label = {
                    Text(stringResource(when (sc) {
                        app.framex.core.common.OverlayScope.Tracked -> R.string.set_scope_tracked
                        app.framex.core.common.OverlayScope.Games -> R.string.set_scope_games
                        app.framex.core.common.OverlayScope.Apps -> R.string.set_scope_apps
                    }))
                })
            }
        }
        val running by vm.watcherRunning.collectAsStateWithLifecycle()
        var usage by remember { mutableStateOf(vm.hasUsageAccess()) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { usage = vm.hasUsageAccess() }
        Note(stringResource(when {
            !granted -> R.string.set_status_perm
            !usage -> R.string.set_status_usage
            running -> R.string.set_status_running
            else -> R.string.set_status_stopped
        }))
        if (granted && usage && !running) {
            OutlinedButton(onClick = vm::startWatcher) { Text(stringResource(R.string.set_status_start)) }
        }
        val seen by vm.seen.collectAsStateWithLifecycle()
        Note(stringResource(R.string.set_seen, seen.pkg ?: "—", stringResource(if (seen.tracked) R.string.set_seen_tracked else R.string.set_seen_untracked)))
        if (granted) OutlinedButton(onClick = vm::testOverlay) { Text(stringResource(R.string.set_test_overlay)) }
        Note(stringResource(R.string.set_test_note))
        val recent by vm.recent.collectAsStateWithLifecycle()
        if (recent.isNotEmpty()) {
            Note(stringResource(R.string.set_recent_title))
            Text(recent.joinToString("\n"), style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
        val fpsDebug by vm.fpsDebug.collectAsStateWithLifecycle()
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        Note(stringResource(R.string.set_fps_debug_title))
        Text(fpsDebug.ifEmpty { "—" }, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        OutlinedButton(onClick = {
            clipboard.setText(androidx.compose.ui.text.AnnotatedString("FrameX FPS diagnostics\n$fpsDebug\n\nrecent:\n" + recent.joinToString("\n")))
        }) { Text(stringResource(R.string.set_fps_debug_copy)) }
        Note(stringResource(R.string.set_keepalive))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text(stringResource(R.string.set_keepalive_battery)) }
            OutlinedButton(onClick = { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text(stringResource(R.string.set_keepalive_app)) }
        }
        Text(stringResource(R.string.set_style_title), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            app.framex.core.common.OverlayStyle.entries.forEach { st ->
                val selected = st == style
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                        .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                        .clickable { onStyle(st) }
                        .background(androidx.compose.ui.graphics.Color(0xFF0B0F14))
                        .padding(10.dp),
                ) {
                    androidx.compose.ui.viewinterop.AndroidView(factory = { c ->
                        app.framex.core.designsystem.overlay.OverlayView(c, st).apply {
                            setContent(app.framex.core.designsystem.overlay.OverlayContent("120", "FPS", app.framex.core.designsystem.overlay.OverlayColors.GOOD, "36.4°C", app.framex.core.designsystem.overlay.OverlayColors.TEXT))
                        }
                    })
                    Text(st.name, style = MaterialTheme.typography.labelMedium, color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.set_capture_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = capture && enabled && granted, enabled = granted && enabled, onCheckedChange = onCapture)
        }
        Note(stringResource(R.string.set_capture_note))
        if (!granted) {
            Note(stringResource(R.string.set_overlay_need))
            OutlinedButton(onClick = {
                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${ctx.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text(stringResource(R.string.set_overlay_grant)) }
        }
    }
}

@Composable
private fun NetworkModeCard(mode: app.framex.core.common.NetworkMode, onChange: (app.framex.core.common.NetworkMode) -> Unit) {
    FrameXCard {
        Text(stringResource(R.string.set_net_title), style = MaterialTheme.typography.titleMedium)
        Note(stringResource(R.string.set_net_note))
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            app.framex.core.common.NetworkMode.entries.forEach { m ->
                FilterChip(selected = mode == m, onClick = { onChange(m) }, label = {
                    Text(stringResource(when (m) {
                        app.framex.core.common.NetworkMode.Local -> R.string.set_net_local
                        app.framex.core.common.NetworkMode.WifiOnly -> R.string.set_net_wifi
                        app.framex.core.common.NetworkMode.MobileOnly -> R.string.set_net_mobile
                        app.framex.core.common.NetworkMode.WifiAndMobile -> R.string.set_net_both
                    }))
                })
            }
        }
        Note(stringResource(if (mode == app.framex.core.common.NetworkMode.Local) R.string.set_net_local_note else R.string.set_net_online_note))
    }
}

@Composable
private fun AutoStopCard(enabled: Boolean, never: Set<String>, vm: SettingsViewModel) {
    val ctx = LocalContext.current
    var method by remember { mutableStateOf(vm.stopMethod()) }
    var picker by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { method = vm.stopMethod() }
    FrameXCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.set_stop_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = enabled, onCheckedChange = vm::setAutoStop)
        }
        Note(stringResource(R.string.set_stop_note))
        Note(stringResource(when (method) {
            app.framex.core.optimization.StopMethod.ShizukuForceStop -> R.string.set_stop_m_shizuku
            app.framex.core.optimization.StopMethod.AccessibilityForceStop -> R.string.set_stop_m_accessibility
            app.framex.core.optimization.StopMethod.BackgroundKill -> R.string.set_stop_m_background
        }))
        if (method == app.framex.core.optimization.StopMethod.BackgroundKill && !vm.a11yBundled()) {
            Note(stringResource(R.string.set_stop_a11y_absent))
        } else if (method == app.framex.core.optimization.StopMethod.BackgroundKill) {
            Note(stringResource(R.string.set_stop_a11y_hint))
            OutlinedButton(onClick = {
                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text(stringResource(R.string.set_stop_a11y_open)) }
        }
        OutlinedButton(onClick = { picker = true }) { Text(stringResource(R.string.set_stop_never, never.size)) }
    }
    if (picker) NeverStopDialog(never, vm) { picker = false }
}

@Composable
private fun NeverStopDialog(current: Set<String>, vm: SettingsViewModel, onClose: () -> Unit) {
    var apps by remember { mutableStateOf<List<app.framex.core.optimization.BackgroundApp>?>(null) }
    val picked = remember { androidx.compose.runtime.mutableStateListOf<String>().also { it.addAll(current) } }
    androidx.compose.runtime.LaunchedEffect(Unit) { apps = vm.userApps() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.set_stop_never_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                Note(stringResource(R.string.set_stop_never_text))
                androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f, fill = false)) {
                    val list = apps.orEmpty()
                    items(list.size) { i ->
                        val a = list[i]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(checked = a.packageName in picked, onCheckedChange = { on -> if (on) picked += a.packageName else picked -= a.packageName })
                            Text(a.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { vm.setNeverStop(picked.toSet()); onClose() }) { Text(stringResource(R.string.set_stop_save)) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text(stringResource(R.string.set_stop_cancel)) } },
    )
}
