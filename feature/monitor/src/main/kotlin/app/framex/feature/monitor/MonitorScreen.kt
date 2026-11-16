package app.framex.feature.monitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import java.util.Locale
import kotlin.math.roundToInt

private val TABS = listOf("Memory", "CPU", "Thermal", "Network")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen(contentPadding: PaddingValues, vm: MonitorViewModel = hiltViewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.padding(top = contentPadding.calculateTopPadding())) {
        PrimaryTabRow(selectedTabIndex = tab) {
            TABS.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        val pad = PaddingValues(16.dp, 16.dp, 16.dp, contentPadding.calculateBottomPadding() + 16.dp)
        when (tab) {
            0 -> MemoryTab(vm, pad)
            1 -> CpuTab(vm, pad)
            2 -> ThermalTab(vm, pad)
            else -> NetworkTab(vm, pad)
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
                "RAM", ram,
                valueText = { "${(it.usedFraction * 100).roundToInt()}% used" },
                detailText = { "Used ${gb(it.usedBytes)} GB · available ${gb(it.availableBytes)} GB · total ${gb(it.totalBytes)} GB" },
                progress = { it.usedFraction },
            )
        }
        if (ram is Metric.Available && Advisor.highMemory(ram.value)) {
            item {
                Warning("High memory usage. Android frees memory by itself; closing apps you don't need may help, but clearing RAM does not raise FPS.")
            }
        }
        item { HistoryCard("RAM used, % (last ${hist.size} samples)", hist, 0f, 100f) }
        item {
            val pss = live.detailed?.ownProcessPssKb
            MetricCard(
                "FrameX process memory", pss,
                valueText = { "${it / 1024} MB" },
                detailText = { "Android only shows memory of FrameX itself. Other apps' processes are hidden from regular apps." },
            )
        }
    }
}

@Composable
private fun CpuTab(vm: MonitorViewModel, pad: PaddingValues) {
    val live by vm.live.collectAsStateWithLifecycle()
    val hist by vm.cpuHistory.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            MetricCard("CPU load", live.snapshot?.cpuLoad, valueText = { "${(it * 100).roundToInt()}%" }, progress = { it })
        }
        if (hist.size >= 2) {
            item { HistoryCard("CPU load, % (last ${hist.size} samples)", hist, 0f, 100f) }
            item {
                FrameXCard {
                    Text("Load analysis", style = MaterialTheme.typography.titleMedium)
                    Text("Average ${hist.average().roundToInt()}% · peak ${hist.max().roundToInt()}% over ${hist.size} samples.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            val cpu = live.detailed?.cpu
            FrameXCard {
                Text("Cores", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (cpu == null) Text("Measuring…") else {
                    Text("${cpu.cores} cores available", style = MaterialTheme.typography.titleLarge)
                    when (val f = cpu.frequencies) {
                        is Metric.Unavailable -> Text(f.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        is Metric.Available -> f.value.forEach {
                            Text("Core ${it.core}: ${it.curKHz?.let { k -> "${k / 1000} MHz" } ?: "n/a"} (max ${it.maxKHz?.let { k -> "${k / 1000} MHz" } ?: "n/a"})", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text("FrameX does not change CPU frequencies; that needs Root.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            MetricCard("Battery temperature", bat, valueText = { String.format(Locale.US, "%.1f °C", it.temperatureC) },
                detailText = { b -> listOfNotNull(b.levelPercent?.let { "$it%" }, if (b.charging) "charging" else null).joinToString(" · ") })
        }
        if (bat is Metric.Available) {
            when (Advisor.batteryTemp(bat.value.temperatureC)) {
                BatteryTempState.Hot -> item { Warning("Device is hot (≥ ${Advisor.HOT_C.roundToInt()} °C). Stop heavy use and let it cool down.") }
                BatteryTempState.Warm -> item { Warning("Device is warm (≥ ${Advisor.WARM_C.roundToInt()} °C). Performance may drop if it keeps heating.") }
                BatteryTempState.Normal -> {}
            }
        }
        item { HistoryCard("Battery temperature, °C (last ${hist.size} samples)", hist, null, null) }
        item {
            MetricCard("Thermal status (Android)", d?.thermalLevel,
                valueText = { it.name },
                detailText = { "Anything above None means Android is limiting performance to cool the device." })
        }
        item {
            MetricCard("Thermal headroom", d?.thermalHeadroom,
                valueText = { "${(it * 100).roundToInt()}% of throttling threshold" },
                progress = { it },
                detailText = { "At 100% Android starts throttling." })
        }
        item {
            FrameXCard {
                Text("Thermal sensors", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (val s = d?.thermalSensors) {
                    null -> Text("Measuring…")
                    is Metric.Unavailable -> { Text("Unavailable", style = MaterialTheme.typography.titleLarge); Text(s.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    is Metric.Available -> s.value.forEach { Text("${it.name}: ${String.format(Locale.US, "%.1f", it.tempC)} °C", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

@Composable
private fun NetworkTab(vm: MonitorViewModel, pad: PaddingValues) {
    val n by vm.net.collectAsStateWithLifecycle()
    val hist by vm.pingHistory.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            FrameXCard {
                Text("Internet diagnostics", style = MaterialTheme.typography.titleMedium)
                Text("Measures TCP connect time to a public server and DNS lookups. This is your internet connection, not the latency to any game server.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PingTarget.entries.forEach { t ->
                        FilterChip(selected = n.target == t, onClick = { vm.setTarget(t) }, label = { Text(t.label) }, enabled = !n.running)
                    }
                }
                Button(onClick = vm::runDiagnostics, enabled = !n.running) { Text(if (n.running) "Running…" else "Run diagnostics") }
            }
        }
        n.error?.let { e -> item { Warning(e) } }
        n.connection?.let { c ->
            item { MetricCard("Connection", c, valueText = { it }) }
        }
        n.ping?.let { p ->
            item {
                FrameXCard {
                    Text("Ping (TCP connect, ${p.sent} attempts)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (p.avgMs == null) Text("No replies · 100% packet loss", style = MaterialTheme.typography.titleLarge)
                    else {
                        Text("${p.avgMs!!.roundToInt()} ms avg", style = MaterialTheme.typography.headlineMedium)
                        Text("min ${p.minMs!!.roundToInt()} · max ${p.maxMs!!.roundToInt()} ms · jitter ${p.jitterMs?.let { "${it.roundToInt()} ms" } ?: "n/a"} · loss ${p.lossPercent.roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        n.dns?.let { r ->
            item {
                FrameXCard {
                    Text("DNS lookup (system resolver)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(r.avgMs?.let { "${it.roundToInt()} ms avg" } ?: "Lookups failed", style = MaterialTheme.typography.headlineMedium)
                    Text("Uses unique names so answers are not cached. Only the DNS server your network assigned is tested.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { HistoryCard("Average ping history, ms (last ${hist.size} runs)", hist, null, null) }
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
    FrameXCard {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

private fun gb(bytes: Long) = String.format(Locale.US, "%.1f", bytes / 1_073_741_824.0)
