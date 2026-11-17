package app.framex.feature.games

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.framex.core.designsystem.FrameXCard

data class VendorTool(val component: ComponentName, val title: String)

/**
 * The phone maker's own game booster (Game Turbo, Game Space, Game Booster ...). Some of them can raise the frame rate
 * or insert frames at driver level, which FrameX cannot do from outside. FrameX only finds the screens that really
 * exist on this phone and opens them; it never claims a feature exists and cannot see what is inside.
 */
object VendorGameTools {
    private val PACKAGES = listOf(
        "com.miui.securitycenter",                 // Xiaomi / Redmi / POCO: Game Turbo
        "com.oplus.games", "com.coloros.gamespaceui", // OPPO / OnePlus / realme: Game Space
        "com.vivo.gamecube",                       // vivo
        "com.samsung.android.game.gametools", "com.samsung.android.game.gamehome", // Samsung
        "com.asus.gamewidget",                     // ASUS
    )

    /** Only screens that look like game tools, can be opened from outside, and are switched on. */
    fun isCandidate(className: String, exported: Boolean, enabled: Boolean) =
        exported && enabled && className.substringAfterLast('.').lowercase().let { "game" in it || "turbo" in it }

    fun discover(context: Context): List<VendorTool> {
        val pm = context.packageManager
        val out = ArrayList<VendorTool>()
        for (pkg in PACKAGES) {
            val info = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES) }.getOrNull() ?: continue
            val app = pm.getApplicationLabel(info.applicationInfo ?: continue).toString()
            info.activities.orEmpty()
                .filter { isCandidate(it.name, it.exported, it.isEnabled) }
                .sortedBy { it.name.length }
                .take(5)
                .forEach { a -> out += VendorTool(ComponentName(pkg, a.name), "$app · ${a.name.substringAfterLast('.')}") }
        }
        return out
    }

    fun open(context: Context, tool: VendorTool): Boolean = runCatching {
        context.startActivity(Intent().setComponent(tool.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)

    private val ActivityInfo.isEnabled get() = enabled
}

@Composable
fun VendorToolsCard() {
    val ctx = LocalContext.current
    val tools = remember { VendorGameTools.discover(ctx) }
    val failed = remember { mutableStateOf(false) }
    FrameXCard {
        Text(stringResource(R.string.vt_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.vt_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (tools.isEmpty()) {
            Text(stringResource(R.string.vt_none), style = MaterialTheme.typography.bodyMedium)
        } else {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tools.forEach { t -> OutlinedButton(onClick = { failed.value = !VendorGameTools.open(ctx, t) }) { Text(t.title) } }
            }
        }
        if (failed.value) Text(stringResource(R.string.vt_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
