package app.framex.feature.home

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.framex.core.designsystem.FrameXCard
import app.framex.core.designsystem.MetricCard
import app.framex.core.designsystem.label
import app.framex.feature.optimize.QuickBoostCard
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
                Label(stringResource(R.string.home_device))
                Text("${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}", style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.home_android, Build.VERSION.RELEASE, Build.VERSION.SDK_INT), style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            val low = stringResource(R.string.home_low_memory)
            MetricCard(
                title = stringResource(R.string.home_ram), metric = snap?.ram,
                valueText = { stringResource(R.string.home_ram_used, (it.usedFraction * 100).roundToInt()) },
                detailText = { stringResource(R.string.home_ram_detail, gb(it.usedBytes), gb(it.totalBytes)) + if (it.lowMemory) low else "" },
                progress = { it.usedFraction },
            )
        }
        item {
            MetricCard(
                title = stringResource(R.string.home_cpu), metric = snap?.cpuLoad,
                valueText = { "${(it * 100).roundToInt()}%" },
                progress = { it },
            )
        }
        item {
            val ch = stringResource(R.string.home_charging)
            MetricCard(
                title = stringResource(R.string.home_battery), metric = snap?.battery,
                valueText = { String.format(Locale.US, "%.1f °C", it.temperatureC) },
                detailText = { b -> listOfNotNull(b.levelPercent?.let { "$it%" }, if (b.charging) ch else null).joinToString(" · ") },
            )
        }
        item {
            MetricCard(
                title = stringResource(R.string.home_refresh), metric = snap?.display,
                valueText = { stringResource(R.string.home_hz, it.currentHz.roundToInt()) },
                detailText = { stringResource(R.string.home_supported, it.supportedHz.joinToString(", ") { r -> "${r.roundToInt()}" }) },
            )
        }
        item {
            FrameXCard {
                Label(stringResource(R.string.home_profile))
                Text((ui.active?.profile ?: ui.profile).label(), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(if (ui.active != null) R.string.home_profile_active else R.string.home_profile_note, ui.profile.label()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            FrameXCard {
                Label(stringResource(R.string.home_shizuku))
                when (snap?.shizuku) {
                    null -> Text(stringResource(R.string.home_checking), style = MaterialTheme.typography.titleMedium)
                    ShizukuInstall.Installed -> {
                        Text(stringResource(R.string.home_shizuku_installed), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.home_shizuku_installed_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ShizukuInstall.NotInstalled -> {
                        Text(stringResource(R.string.home_shizuku_missing), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.home_shizuku_missing_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            FrameXCard {
                Label(stringResource(R.string.home_recent))
                Text(stringResource(R.string.home_no_sessions), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun Label(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

private fun gb(bytes: Long) = String.format(Locale.US, "%.1f", bytes / 1_073_741_824.0)
