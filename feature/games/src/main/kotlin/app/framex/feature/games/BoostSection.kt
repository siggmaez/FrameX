package app.framex.feature.games

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.framex.core.common.OptAction
import app.framex.core.database.GameProfileEntity
import app.framex.core.database.GameSessionEntity
import app.framex.core.optimization.BoostLevel
import app.framex.core.optimization.GameBoosts
import app.framex.core.optimization.GameTip
import java.util.Locale
import kotlin.math.roundToInt

@Composable
private fun GameTip.text() = stringResource(when (this) {
    GameTip.FrameRateMax -> R.string.tip_frame_max
    GameTip.HighFrameRateMode -> R.string.tip_high_fps
    GameTip.GraphicsLow -> R.string.tip_graphics_low
    GameTip.ShadowsOff -> R.string.tip_shadows_off
    GameTip.ResolutionLow -> R.string.tip_resolution_low
    GameTip.RenderDistance -> R.string.tip_render_distance
})

@Composable
private fun BoostLevel.label() = stringResource(when (this) {
    BoostLevel.Off -> R.string.gb_level_off
    BoostLevel.Safe -> R.string.gb_level_safe
    BoostLevel.Strong -> R.string.gb_level_strong
    BoostLevel.Max -> R.string.gb_level_max
})

/**
 * Per-game boost: what each level really does, the settings only the player can change inside the game,
 * and the measured FPS with and without the boost.
 */
@Composable
fun BoostSection(g: GameProfileEntity, sessions: List<GameSessionEntity>, onPing: (Boolean) -> Unit, onLevel: (BoostLevel) -> Unit) {
    val spec = GameBoosts.specFor(g.packageName)
    val special = GameBoosts.isSpecialized(g.packageName)
    val level = runCatching { BoostLevel.valueOf(g.boostLevel) }.getOrDefault(BoostLevel.Strong)
    val plan = GameBoosts.plan(g.packageName, level)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(if (special) R.string.gb_title_special else R.string.gb_title_generic), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.gb_honest), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BoostLevel.entries.forEach { l -> FilterChip(selected = l == level, onClick = { onLevel(l) }, label = { Text(l.label()) }) }
        }
        if (plan == null) {
            Text(stringResource(R.string.gb_does_nothing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(stringResource(R.string.gb_does_refresh), style = MaterialTheme.typography.bodySmall)
            if (OptAction.FixedPerformance in plan.extraActions) Text(stringResource(R.string.gb_does_fixed), style = MaterialTheme.typography.bodySmall)
            plan.renderScale?.let { Text(stringResource(R.string.gb_does_scale, (it * 100).roundToInt()), style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(R.string.gb_needs_shell), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        spec.knownCapFps?.let { Text(stringResource(R.string.gb_cap, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(stringResource(R.string.pb_game_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = g.pingBoost, onCheckedChange = onPing)
        }
        Text(stringResource(R.string.pb_game_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.gb_tips_title), style = MaterialTheme.typography.labelLarge)
        spec.tips.forEach { Text("• " + it.text(), style = MaterialTheme.typography.bodySmall) }
        val c = BoostStats.compare(sessions)
        Text(stringResource(R.string.gb_cmp_title), style = MaterialTheme.typography.labelLarge)
        val f = { v: Float -> String.format(Locale.US, "%.0f", v) }
        if (c.boostedAvg != null && c.plainAvg != null) {
            Text(stringResource(R.string.gb_cmp_both, f(c.boostedAvg), c.boostedSessions, f(c.plainAvg), c.plainSessions) +
                if (c.estimated) " " + stringResource(R.string.gb_cmp_est) else "", style = MaterialTheme.typography.bodySmall)
        } else {
            Text(stringResource(R.string.gb_cmp_need), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
