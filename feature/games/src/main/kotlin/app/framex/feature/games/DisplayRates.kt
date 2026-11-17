package app.framex.feature.games

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/** The refresh rates (Hz) this screen really supports, lowest first; empty if they cannot be read. */
fun supportedRefreshRates(context: Context): List<Int> = runCatching {
    (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        .supportedModes.map { Math.round(it.refreshRate) }.distinct().sorted()
}.getOrDefault(emptyList())
