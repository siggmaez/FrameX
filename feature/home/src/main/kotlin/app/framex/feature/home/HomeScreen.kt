package app.framex.feature.home

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.framex.core.common.Metric
import app.framex.core.designsystem.FrameXCard
import app.framex.core.designsystem.MetricCard
import app.framex.core.monitoring.ShizukuInstall
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun HomeScreen(contentPadding: PaddingValues, vm: HomeViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val snap = ui.snapshot
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
                Text("This device", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}", style = MaterialTheme.typography.titleLarge)
                Text("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            MetricCard(
                title = "RAM", metric = snap?.ram,
                valueText = { "${(it.usedFraction * 100).roundToInt()}% used" },
                detailText = { "${gb(it.usedBytes)} of ${gb(it.totalBytes)} GB used" + if (it.lowMemory) " · system reports low memory" else "" },
                progress = { it.usedFraction },
            )
        }
        item {
            MetricCard(
                title = "CPU load", metric = snap?.cpuLoad,
                valueText = { "${(it * 100).roundToInt()}%" },
                progress = { it },
            )
        }
        item {
            MetricCard(
                title = "Battery temperature", metric = snap?.battery,
                valueText = { String.format(Locale.US, "%.1f °C", it.temperatureC) },
                detailText = { b ->
                    listOfNotNull(b.levelPercent?.let { "$it%" }, if (b.charging) "charging" else null).joinToString(" · ")
                },
            )
        }
        item {
            MetricCard(
                title = "Refresh rate", metric = snap?.display,
                valueText = { "${it.currentHz.roundToInt()} Hz" },
                detailText = { "Supported: " + it.supportedHz.joinToString(", ") { r -> "${r.roundToInt()}" } + " Hz" },
            )
        }
        item {
            FrameXCard {
                Text("Selected profile", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(profileName(ui.profile.name), style = MaterialTheme.typography.titleLarge)
                Text("The profile is stored as your choice; it is not applied to the system yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            FrameXCard {
                Text("Shizuku", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (snap?.shizuku) {
                    null -> Text("Checking…", style = MaterialTheme.typography.titleMedium)
                    ShizukuInstall.Installed -> {
                        Text("Installed", style = MaterialTheme.typography.titleLarge)
                        Text("Connection and permission are not checked yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ShizukuInstall.NotInstalled -> {
                        Text("Not installed", style = MaterialTheme.typography.titleLarge)
                        Text("Optional. FrameX works without it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            FrameXCard {
                Text("Recent gaming session", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("No sessions recorded", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private fun gb(bytes: Long) = String.format(Locale.US, "%.1f", bytes / 1_073_741_824.0)

private fun profileName(n: String) = when (n) {
    "BatterySaver" -> "Battery Saver"
    else -> n
}
