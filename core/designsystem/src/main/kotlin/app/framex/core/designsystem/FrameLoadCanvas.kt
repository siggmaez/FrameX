package app.framex.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animated render load that reports the real time between frames delivered to this window.
 * Used for FrameX's own frame test and graphics benchmark; it says nothing about other apps.
 */
@Composable
fun FrameLoadCanvas(running: Boolean, shapes: Int, onFrameTime: (Float) -> Unit, modifier: Modifier = Modifier) {
    var phase by remember { mutableLongStateOf(0L) }
    val color = MaterialTheme.colorScheme.primary
    val color2 = MaterialTheme.colorScheme.tertiary
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { t ->
                if (last != 0L) onFrameTime((t - last) / 1_000_000f)
                last = t
                phase = t
            }
        }
    }
    Canvas(modifier.fillMaxWidth().height(160.dp)) {
        val t = phase / 1_000_000_000.0
        for (i in 0 until shapes) {
            val a = t * (0.5 + i % 7 * 0.15) + i
            val cx = size.width * (0.5f + 0.45f * cos(a).toFloat() * ((i % 5 + 1) / 5f))
            val cy = size.height * (0.5f + 0.45f * sin(a * 1.3).toFloat() * ((i % 3 + 1) / 3f))
            drawCircle(if (i % 2 == 0) color else color2, radius = 6f + i % 9, center = Offset(cx, cy), alpha = 0.55f)
        }
    }
}
