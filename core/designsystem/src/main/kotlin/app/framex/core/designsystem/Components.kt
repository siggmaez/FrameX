package app.framex.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.framex.core.common.Metric

/** Card showing one metric. Shows an explicit unavailable state (with reason) when there is no real value. */
@Composable
fun <T> MetricCard(
    title: String,
    metric: Metric<T>?,
    modifier: Modifier = Modifier,
    valueText: (T) -> String,
    detailText: ((T) -> String)? = null,
    progress: ((T) -> Float)? = null,
) {
    FrameXCard(modifier) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (metric) {
            null -> Text("Measuring…", style = MaterialTheme.typography.titleMedium)
            is Metric.Unavailable -> {
                Text("Unavailable", style = MaterialTheme.typography.titleLarge)
                Text(metric.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is Metric.Available -> {
                Text(valueText(metric.value), style = MaterialTheme.typography.headlineMedium)
                progress?.let {
                    LinearProgressIndicator(progress = { it(metric.value).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
                detailText?.let {
                    Text(it(metric.value), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun FrameXCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}
