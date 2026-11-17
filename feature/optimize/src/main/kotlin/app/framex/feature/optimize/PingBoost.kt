package app.framex.feature.optimize

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.framex.core.common.ConnectionKind
import app.framex.core.common.NetworkMode
import app.framex.core.common.OptAction
import app.framex.core.common.OptimizationProfile
import app.framex.core.datastore.SettingsRepository
import app.framex.core.designsystem.FrameXCard
import app.framex.core.monitoring.NetResult
import app.framex.core.monitoring.NetStatus
import app.framex.core.monitoring.NetworkDiagnostics
import app.framex.core.monitoring.PingTarget
import app.framex.core.optimization.ActionResult
import app.framex.core.optimization.ApplyRequest
import app.framex.core.optimization.BackgroundAppsProvider
import app.framex.core.optimization.BoostRunner
import app.framex.core.optimization.OptimizationEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

enum class PingStep { Measuring, Applying, MeasuringAgain }
enum class PingMsg { Local, NoConnection, Blocked }

data class PingBoostUi(
    val running: Boolean = false,
    val step: PingStep? = null,
    val before: NetResult? = null,
    val after: NetResult? = null,
    val applied: List<ActionResult> = emptyList(),
    val message: PingMsg? = null,
)

/** Percent change from [before] to [after]; negative = lower ping = better. Null if either is unknown. */
object PingDelta {
    fun percent(before: Float?, after: Float?): Float? =
        if (before == null || after == null || before <= 0f) null else (after - before) / before * 100f
}

@HiltViewModel
class PingBoostViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val network: NetworkDiagnostics,
    private val engine: OptimizationEngine,
    private val apps: BackgroundAppsProvider,
    private val runner: BoostRunner,
) : ViewModel() {
    private val _ui = MutableStateFlow(PingBoostUi())
    val ui: StateFlow<PingBoostUi> = _ui.asStateFlow()

    /** Measures, applies the lag-reducing actions, measures again. Nothing is claimed that was not measured. */
    fun boost() {
        if (_ui.value.running) return
        viewModelScope.launch {
            val s = settings.settings.first()
            if (s.networkMode == NetworkMode.Local) { _ui.value = PingBoostUi(message = PingMsg.Local); return@launch }
            _ui.value = PingBoostUi(running = true, step = PingStep.Measuring)
            val before = network.pingActive(PingTarget.Cloudflare, s.networkMode)
            if (before == null) { _ui.value = PingBoostUi(message = PingMsg.NoConnection); return@launch }
            if (before.status == NetStatus.Blocked) { _ui.value = PingBoostUi(message = PingMsg.Blocked); return@launch }
            _ui.update { it.copy(step = PingStep.Applying, before = before) }
            val stop = if (s.autoStopBackground) apps.autoList(emptySet(), runner.suggestedLimit()).map { it.packageName } else emptyList()
            val actions = setOf(OptAction.LowLatencyWifi, OptAction.WifiLowLatencyMode, OptAction.BackgroundDataLimit, OptAction.StopBackgroundApps)
            val report = engine.apply(OptimizationProfile.Custom, ApplyRequest(stopPackages = stop, customActions = actions))
            delay(1200); runner.dismiss()
            delay(1500) // let the radio and the background traffic settle
            _ui.update { it.copy(step = PingStep.MeasuringAgain, applied = report.results) }
            val after = network.pingActive(PingTarget.Cloudflare, s.networkMode)
            _ui.update { it.copy(running = false, step = null, after = after) }
        }
    }
}

@Composable
fun PingBoostCard(vm: PingBoostViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    FrameXCard {
        Text(stringResource(R.string.pb_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.pb_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = vm::boost, enabled = !ui.running) {
            Text(stringResource(when (ui.step) {
                PingStep.Measuring -> R.string.pb_s_measure
                PingStep.Applying -> R.string.pb_s_apply
                PingStep.MeasuringAgain -> R.string.pb_s_again
                null -> R.string.pb_button
            }))
        }
        ui.message?.let {
            Text(stringResource(when (it) {
                PingMsg.Local -> R.string.pb_local
                PingMsg.NoConnection -> R.string.pb_no_conn
                PingMsg.Blocked -> R.string.pb_blocked
            }), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        ui.before?.let { PingLine(stringResource(R.string.pb_before), it) }
        ui.after?.let { a ->
            PingLine(stringResource(R.string.pb_after), a)
            val d = PingDelta.percent(ui.before?.ping?.avgMs, a.ping?.avgMs)
            Text(
                if (d == null) stringResource(R.string.pb_no_delta)
                else stringResource(if (d <= 0) R.string.pb_delta_better else R.string.pb_delta_worse, String.format(Locale.US, "%.0f", kotlin.math.abs(d))),
                style = MaterialTheme.typography.titleSmall,
                color = if (d != null && d <= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(stringResource(R.string.pb_honest), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (ui.applied.isNotEmpty()) {
            Text(stringResource(R.string.opt_result), style = MaterialTheme.typography.labelLarge)
            ui.applied.forEach { ResultRow(it) }
            Text(stringResource(R.string.pb_stays), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PingLine(title: String, r: NetResult) {
    val kind = stringResource(if (r.kind == ConnectionKind.Wifi) R.string.pb_wifi else R.string.pb_mobile)
    val p = r.ping
    Text(
        if (p?.avgMs == null) stringResource(R.string.pb_line_none, title, kind)
        else stringResource(R.string.pb_line, title, kind, p.avgMs!!.roundToInt(), p.jitterMs?.roundToInt() ?: 0, p.lossPercent.roundToInt()),
        style = MaterialTheme.typography.bodyMedium,
    )
}
