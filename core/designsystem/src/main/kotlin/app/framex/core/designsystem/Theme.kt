package app.framex.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.framex.core.common.ThemeMode

private val FallbackLight = lightColorScheme(
    primary = Color(0xFF3F5F90), secondary = Color(0xFF565F71), tertiary = Color(0xFF6F5675),
)
private val FallbackDark = darkColorScheme(
    primary = Color(0xFFA8C8FF), secondary = Color(0xFFBEC6DC), tertiary = Color(0xFFDDBCE0),
)

/** Material 3 theme. Uses Monet dynamic colors on Android 12+, static colors on Android 10/11. */
@Composable
fun FrameXTheme(themeMode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val ctx = LocalContext.current
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) FallbackDark else FallbackLight
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
