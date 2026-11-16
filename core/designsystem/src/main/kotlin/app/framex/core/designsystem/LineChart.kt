package app.framex.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Simple line chart of real samples (oldest first). Shows a message instead of a line when data is insufficient. */
@Composable
fun LineChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    fixedMin: Float? = null,
    fixedMax: Float? = null,
) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Box(modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
        if (values.size < 2) {
            Text("Collecting data…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val lo = fixedMin ?: values.min()
            var hi = fixedMax ?: values.max()
            if (hi - lo < 1f) hi = lo + 1f
            Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 2f)
                drawLine(grid, Offset(0f, 0f), Offset(size.width, 0f), 2f)
                val path = Path()
                values.forEachIndexed { i, v ->
                    val x = size.width * i / (values.size - 1)
                    val y = size.height - size.height * ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, line, style = Stroke(width = 4f, cap = StrokeCap.Round))
            }
        }
    }
}
