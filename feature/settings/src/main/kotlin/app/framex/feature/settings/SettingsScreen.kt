package app.framex.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
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
                Text("Theme", style = MaterialTheme.typography.titleMedium)
                Text("Dynamic colors follow your wallpaper on Android 12+.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { m ->
                        FilterChip(selected = s.themeMode == m, onClick = { vm.setTheme(m) }, label = { Text(m.name) })
                    }
                }
            }
        }
        item {
            FrameXCard {
                Text("Monitoring interval", style = MaterialTheme.typography.titleMedium)
                Text("How often live metrics refresh while a screen is visible. Longer intervals use less battery. Nothing is polled in the background.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1000L, 2000L, 5000L).forEach { ms ->
                        FilterChip(selected = s.monitoringIntervalMs == ms, onClick = { vm.setInterval(ms) }, label = { Text("${ms / 1000} s") })
                    }
                }
            }
        }
        item {
            FrameXCard {
                Text("Permissions", style = MaterialTheme.typography.titleMedium)
                Text("FrameX currently requests no runtime permissions. It only reads battery, memory and display information that Android gives every app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
