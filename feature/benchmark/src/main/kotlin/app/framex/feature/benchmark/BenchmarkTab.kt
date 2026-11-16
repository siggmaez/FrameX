package app.framex.feature.benchmark

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.framex.core.benchmark.BenchCodec
import app.framex.core.benchmark.BenchCompare
import app.framex.core.benchmark.BenchType
import app.framex.core.common.OptimizationProfile
import app.framex.core.database.BenchmarkResultEntity
import app.framex.core.designsystem.FrameLoadCanvas
import app.framex.core.designsystem.FrameXCard
import app.framex.core.designsystem.label
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun BenchType.title() = stringResource(when (this) {
    BenchType.Cpu -> R.string.b_cpu
    BenchType.Ram -> R.string.b_ram
    BenchType.Storage -> R.string.b_storage
    BenchType.Graphics -> R.string.b_graphics
})

@Composable
private fun BenchType.note() = stringResource(when (this) {
    BenchType.Cpu -> R.string.b_cpu_note
    BenchType.Ram -> R.string.b_ram_note
    BenchType.Storage -> R.string.b_storage_note
    BenchType.Graphics -> R.string.b_graphics_note
})

@Composable
fun BenchmarkTab(pad: PaddingValues, vm: BenchmarkViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            FrameXCard { Text(stringResource(R.string.b_banner), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        ui.hotTempC?.let { t -> item { FrameXCard { Text(stringResource(R.string.b_hot, String.format(Locale.US, "%.1f", t)), color = MaterialTheme.colorScheme.error) } } }
        ui.error?.let { e -> item { FrameXCard { Text(stringResource(R.string.b_failed, e), color = MaterialTheme.colorScheme.error) } } }
        BenchType.entries.forEach { type ->
            item(key = type.name) {
                FrameXCard {
                    Text(type.title(), style = MaterialTheme.typography.titleMedium)
                    Text(type.note(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (type == BenchType.Graphics && ui.running == BenchType.Graphics) {
                        FrameLoadCanvas(running = true, shapes = 1500, onFrameTime = vm::onFrame)
                    }
                    if (ui.running == type) LinearProgressIndicator(progress = { ui.progress }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { vm.run(type) }, enabled = ui.running == null) {
                        Text(stringResource(if (ui.running == type) R.string.b_running else R.string.b_run))
                    }
                }
            }
        }
        item { Text(stringResource(R.string.b_history), style = MaterialTheme.typography.titleMedium) }
        item { CompareCard(ui) }
        if (ui.results.isEmpty()) item { Text(stringResource(R.string.b_empty), style = MaterialTheme.typography.bodySmall) }
        else item { Text(stringResource(R.string.b_select_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(ui.results.size, key = { ui.results[it].id }) { i ->
            val r = ui.results[i]
            ResultRow(r, r.id in ui.selected) { vm.toggle(r.id) }
        }
        if (ui.results.isNotEmpty()) item { OutlinedButton(onClick = vm::clear) { Text(stringResource(R.string.b_clear)) } }
    }
}

@Composable
private fun profileText(r: BenchmarkResultEntity): String =
    r.profile?.let { n -> OptimizationProfile.entries.firstOrNull { it.name == n }?.label() ?: n } ?: stringResource(R.string.b_no_profile)

@Composable
private fun ResultRow(r: BenchmarkResultEntity, selected: Boolean, onToggle: () -> Unit) {
    val type = BenchType.valueOf(r.type)
    val d = BenchCodec.decode(r.details)
    FrameXCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text("${type.title()} · ${stringResource(R.string.b_score, String.format(Locale.US, "%.1f", r.score), r.unit)}", style = MaterialTheme.typography.titleSmall)
                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.timestamp)) + " · " + stringResource(R.string.b_profile, profileText(r)), style = MaterialTheme.typography.bodySmall)
                Text(d.entries.joinToString(" · ") { "${it.key} ${it.value}" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(r.deviceInfo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CompareCard(ui: BenchUi) {
    if (ui.selected.size < 2) return
    val picked = ui.results.filter { it.id in ui.selected }.sortedBy { it.timestamp }
    if (picked.size < 2) return
    val (before, after) = picked
    FrameXCard {
        Text(stringResource(R.string.b_compare), style = MaterialTheme.typography.titleMedium)
        when {
            before.type != after.type -> Text(stringResource(R.string.b_diff_type), color = MaterialTheme.colorScheme.error)
            before.deviceInfo != after.deviceInfo -> Text(stringResource(R.string.b_diff_device), color = MaterialTheme.colorScheme.error)
            else -> {
                Text(stringResource(R.string.b_before, stringResource(R.string.b_score, String.format(Locale.US, "%.1f", before.score), before.unit) + " · " + profileText(before)))
                Text(stringResource(R.string.b_after, stringResource(R.string.b_score, String.format(Locale.US, "%.1f", after.score), after.unit) + " · " + profileText(after)))
                val delta = BenchCompare.deltaPercent(before.score, after.score)
                if (delta != null) {
                    Text(stringResource(R.string.b_change, String.format(Locale.US, "%+.1f", delta)), style = MaterialTheme.typography.titleLarge,
                        color = if (delta >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

