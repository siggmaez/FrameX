package app.framex.feature.monitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.framex.core.common.Metric
import app.framex.core.designsystem.FrameXCard
import app.framex.core.designsystem.LineChart
import app.framex.core.designsystem.MetricCard
import app.framex.core.monitoring.Advisor
import app.framex.core.monitoring.BatteryTempState
import app.framex.core.monitoring.PingTarget
import app.framex.core.monitoring.ThermalLevel
import java.util.Locale
import kotlin.math.roundToInt

private val TAB_LABELS = listOf(R.string.tab_memory, R.string.tab_cpu, R.string.tab_thermal, R.string.tab_network, R.string.tab_fps, R.string.tab_bench)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen(contentPadding: PaddingValues, vm: MonitorViewModel = hiltViewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.padding(top = contentPadding.calculateTopPadding())) {
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            TAB_LABELS.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(t)) }) }
        }
        val pad = PaddingValues(16.dp, 16.dp, 16.dp, contentPadding.calculateBottomPadding() + 16.dp)
        when (tab) {
            0 -> MemoryTab(vm, pad)
            1 -> CpuTab(vm, pad)
            2 -> ThermalTab(vm, pad)
            3 -> NetworkTab(vm, pad)
            4 -> FpsTab(vm, pad)
            else -> app.framex.feature.benchmark.BenchmarkTab(pad)
        }
    }
}

@Composable
private fun MemoryTab(vm: MonitorViewModel, pad: PaddingValues) {
    val live by vm.live.collectAsStateWithLifecycle()
    val hist by vm.ramHistory.collectAsStateWithLifecycle()
    val ram = live.snapshot?.ram
    LazyColumn(contentPadding = pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            MetricCard(
                stringResource(R.string.mem_ram), ram,
                valueText = { stringResource(R.string.mem_used_pct, (it.usedFraction * 100).roundToInt()) },
                detailText = { stringResource(R.string.mem_detail, gb(it.usedBytes), gb(it.availableBytes), gb(it.totalBytes)) },
                progress = { it.usedFraction },
            )
        }
        if (ram is Metric.Available && Advisor.highMemory(ram.value)) item { Warning(stringResource(R.string.mem_warning)) }
        item { HistoryCard(stringResource(R.string.mem_history, hist.size), hist, 0f, 100f) }
        item {
            MetricCard(
                stringResource(R.string.mem_own), live.detailed?.ownProcessPssKb,
                valueText = { "${it / 1024} MB" },
                detailText = { stringResource(R.string.mem_own_note) },
            )
        }
    }
}

@Composable
private fun CpuTab(vm: MonitorViewModel, pad: PaddingValues) {
    val live by vm.live.collectAsStateWithLifecycle()
    val hist by vm.cpuHistory.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { MetricCard(stringResource(R.string.cpu_load), live.snapshot?.cpuLoad, valueText = { "${(it * 100).roundToInt()}%" }, progress = { it }) }
        if (hist.size >= 2) {
            item { HistoryCard(stringResource(R.string.cpu_history, hist.size), hist, 0f, 100f) }
            item {
                FrameXCard {
                    Text(stringResource(R.string.cpu_analysis), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.cpu_analysis_text, hist.average().roundToInt(), hist.max().roundToInt(), hist.size), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            val cpu = live.detailed?.cpu
            val na = stringResource(R.string.na_short)
            FrameXCard {
                Text(stringResource(R.string.cpu_cores), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (cpu == null) Text(stringResource(app.framex.core.designsystem.R.string.ds_measuring)) else {
                    Text(stringResource(R.string.cpu_cores_count, cpu.cores), style = MaterialTheme.typography.titleLarge)
                    when (val f = cpu.frequencies) {
                        is Metric.Unavailable -> Text(f.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        is Metric.Available -> f.value.forEach {
                            Text(stringResource(R.string.cpu_core_line, it.core, it.curKHz?.let { k -> "${k / 1000} MHz" } ?: na, it.maxKHz?.let { k -> "${k / 1000} MHz" } ?: na), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text(stringResource(R.string.cpu_no_change), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ThermalTab(vm: MonitorViewModel, pad: PaddingValues) {
    val live by vm.live.collectAsStateWithLifecycle()
    val hist by vm.tempHistory.collectAsStateWithLifecycle()
    val bat = live.snapshot?.battery
    val d = live.detailed
    LazyColumn(contentPadding = pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            val ch = stringResource(R.string.th_charging)
            MetricCard(stringResource(R.string.th_battery), bat, valueText = { String.format(Locale.US, "%.1f °C", it.temperatureC) },
                detailText = { b -> listOfNotNull(b.levelPercent?.let { "$it%" }, if (b.charging) ch else null).joinToString(" · ") })
        }
        if (bat is Metric.Available) {
            when (Advisor.batteryTemp(bat.value.temperatureC)) {
                BatteryTempState.Hot -> item { Warning(stringResource(R.string.th_hot, Advisor.HOT_C.roundToInt())) }
                BatteryTempState.Warm -> item { Warning(stringResource(R.string.th_warm, Advisor.WARM_C.roundToInt())) }
                BatteryTempState.Normal -> {}
            }
        }
        item { HistoryCard(stringResource(R.string.th_history, hist.size), hist, null, null) }
        item {
            MetricCard(stringResource(R.string.th_status), d?.thermalLevel,
                valueText = { stringResource(levelRes(it)) },
                detailText = { stringResource(R.string.th_status_note) })
        }
        item {
            MetricCard(stringResource(R.string.th_headroom), d?.thermalHeadroom,
                valueText = { stringResource(R.string.th_headroom_value, (it * 100).roundToInt()) },
                progress = { it },
                detailText = { stringResource(R.string.th_headroom_note) })
        }
        item {
            FrameXCard {
                Text(stringResource(R.string.th_sensors), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (val s = d?.thermalSensors) {
                    null -> Text(stringResource(app.framex.core.designsystem.R.string.ds_measuring))
                    is Metric.Unavailable -> {
                        Text(stringResource(app.framex.core.designsystem.R.string.ds_unavailable), style = MaterialTheme.typography.titleLarge)
                        Text(s.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is Metric.Available -> s.value.forEach { Text("${it.name}: ${String.format(Locale.US, "%.1f", it.tempC)} °C", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

private fun levelRes(l: ThermalLevel) = when (l) {
    ThermalLevel.None -> R.string.th_level_none
    ThermalLevel.Light -> R.string.th_level_light
    ThermalLevel.Moderate -> R.string.th_level_moderate
    ThermalLevel.Severe -> R.string.th_level_severe
    ThermalLevel.Critical -> R.string.th_level_critical
    ThermalLevel.Emergency -> R.string.th_level_emergency
    ThermalLevel.Shutdown -> R.string.th_level_shutdown
}

@Composable
private fun NetworkTab(vm: MonitorViewModel, pad: PaddingValues) {
    val n by vm.net.collectAsStateWithLifecycle()
    val hist by vm.pingHistory.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            FrameXCard {
                Text(stringResource(R.string.net_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.net_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PingTarget.entries.forEach { t ->
                        FilterChip(selected = n.target == t, onClick = { vm.setTarget(t) }, label = { Text(t.label) }, enabled = !n.running)
                    }
                }
                Button(onClick = vm::runDiagnostics, enabled = !n.running) {
                    Text(stringResource(if (n.running) R.string.net_running else R.string.net_run))
                }
            }
        }
        n.error?.let { e -> item { Warning(stringResource(R.string.net_failed, e)) } }
        n.connection?.let { c -> item { MetricCard(stringResource(R.string.net_connection), c, valueText = { it }) } }
        n.ping?.let { p ->
            item {
                val na = stringResource(R.string.na_short)
                FrameXCard {
                    Text(stringResource(R.string.net_ping_title, p.sent), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (p.avgMs == null) Text(stringResource(R.string.net_no_replies), style = MaterialTheme.typography.titleLarge)
                    else {
                        Text(stringResource(R.string.net_avg, p.avgMs!!.roundToInt()), style = MaterialTheme.typography.headlineMedium)
                        Text(stringResource(R.string.net_ping_detail, p.minMs!!.roundToInt(), p.maxMs!!.roundToInt(),
                            p.jitterMs?.let { "${it.roundToInt()}" } ?: na, p.lossPercent.roundToInt()), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        n.dns?.let { r ->
            item {
                FrameXCard {
                    Text(stringResource(R.string.net_dns_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(r.avgMs?.let { stringResource(R.string.net_avg, it.roundToInt()) } ?: stringResource(R.string.net_dns_failed), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.net_dns_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { HistoryCard(stringResource(R.string.net_history, hist.size), hist, null, null) }
    }
}

@Composable
private fun HistoryCard(title: String, values: List<Float>, min: Float?, max: Float?) {
    FrameXCard {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LineChart(values, fixedMin = min, fixedMax = max)
    }
}

@Composable
private fun Warning(text: String) {
    FrameXCard { Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
}

private fun gb(bytes: Long) = String.format(Locale.US, "%.1f", bytes / 1_073_741_824.0)

@Composable
private fun FpsTab(vm: MonitorViewModel, pad: PaddingValues) {
    val t by vm.frameTest.collectAsStateWithLifecycle()
    val last by vm.lastSession.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            FrameXCard {
                Text(stringResource(R.string.fps_test_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.fps_test_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                app.framex.core.designsystem.FrameLoadCanvas(running = t.running, shapes = 400, onFrameTime = vm::onFrame)
                Button(onClick = { vm.startFrameTest() }, enabled = !t.running) {
                    Text(if (t.running) stringResource(R.string.fps_test_running, t.secondsLeft) else stringResource(R.string.fps_test_start))
                }
                if (t.running && t.liveFps != null) Text(stringResource(R.string.fps_current, "%.0f".format(Locale.US, t.liveFps)), style = MaterialTheme.typography.titleLarge)
            }
        }
        t.stats?.let { s ->
            item {
                FrameXCard {
                    Text(stringResource(R.string.fps_label_measured), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    val na = stringResource(R.string.fps_need_samples)
                    val none = stringResource(R.string.na_short)
                    Stat(stringResource(R.string.fps_avg), "%.1f".format(Locale.US, s.avgFps))
                    Stat(stringResource(R.string.fps_min), s.minFps?.let { "%.0f".format(Locale.US, it) } ?: none)
                    Stat(stringResource(R.string.fps_low), s.onePercentLowFps?.let { "%.1f".format(Locale.US, it) } ?: na)
                    Stat(stringResource(R.string.fps_frame_time), stringResource(R.string.fps_frame_time_value, "%.1f".format(Locale.US, s.avgFrameMs), "%.1f".format(Locale.US, s.maxFrameMs)))
                    Stat(stringResource(R.string.fps_stability), "%.1f%%".format(Locale.US, s.stabilityPercent))
                }
            }
            item { HistoryCard(stringResource(R.string.fps_graph), t.graph, 0f, null) }
        }
        item {
            FrameXCard {
                Text(stringResource(R.string.fps_game_title), style = MaterialTheme.typography.titleMedium)
                val ls = last
                if (ls?.avgFps != null) {
                    val f = { v: Float? -> v?.let { "%.0f".format(Locale.US, it) } ?: "—" }
                    Text(stringResource(R.string.fps_game_measured, f(ls.avgFps), f(ls.minFps), f(ls.lowFps)), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(stringResource(R.string.fps_game_unavailable), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(if (vm.shizukuAvailable) R.string.fps_game_no_session else R.string.fps_game_need_shizuku), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(R.string.fps_game_estimates), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
