package app.framex.feature.optimize

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.framex.core.common.Metric
import app.framex.core.common.OptAction
import app.framex.core.common.OptimizationProfile
import app.framex.core.database.OptimizationLogEntity
import app.framex.core.database.OptimizationLogRepository
import app.framex.core.datastore.EngineSnapshot
import app.framex.core.datastore.EngineStateStore
import app.framex.core.datastore.SettingsRepository
import app.framex.core.monitoring.SystemMetricsSource
import app.framex.core.optimization.ApplyRequest
import app.framex.core.optimization.BackgroundApp
import app.framex.core.optimization.BackgroundAppsProvider
import app.framex.core.optimization.Capability
import app.framex.core.optimization.CapabilityProvider
import app.framex.core.optimization.EngineReport
import app.framex.core.optimization.OptimizationEngine
import app.framex.core.optimization.PrivilegedShell
import app.framex.core.optimization.Profiles
import app.framex.core.optimization.SmartBoost
import app.framex.core.optimization.SmartRecommendation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConfirmStop(val profile: OptimizationProfile, val apps: List<BackgroundApp>)

private data class Local(
    val capabilities: Map<OptAction, Capability> = emptyMap(),
    val busy: Boolean = false,
    val report: EngineReport? = null,
    val confirm: ConfirmStop? = null,
    val smart: SmartRecommendation? = null,
)

data class OptimizeUiState(
    val selected: OptimizationProfile = OptimizationProfile.Balanced,
    val customActions: Set<OptAction> = emptySet(),
    val capabilities: Map<OptAction, Capability> = emptyMap(),
    val active: EngineSnapshot? = null,
    val busy: Boolean = false,
    val report: EngineReport? = null,
    val log: List<OptimizationLogEntity> = emptyList(),
    val confirm: ConfirmStop? = null,
    val smart: SmartRecommendation? = null,
)

@HiltViewModel
class OptimizeViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val engine: OptimizationEngine,
    private val capabilities: CapabilityProvider,
    private val apps: BackgroundAppsProvider,
    private val source: SystemMetricsSource,
    private val shell: PrivilegedShell,
    private val runner: app.framex.core.optimization.BoostRunner,
    store: EngineStateStore,
    logs: OptimizationLogRepository,
) : ViewModel() {
    private val local = MutableStateFlow(Local(capabilities = capabilities.detect()))

    val state: StateFlow<OptimizeUiState> = combine(settings.settings, store.snapshot, logs.recent(30), local) { s, active, log, l ->
        OptimizeUiState(s.selectedProfile, s.customActions, l.capabilities, active, l.busy, l.report, log, l.confirm, l.smart)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OptimizeUiState())

    /** One tap: stop every eligible background app now. Never-stop and protected apps are skipped. */
    fun taskKill() {
        viewModelScope.launch {
            local.update { it.copy(busy = true) }
            val limit = if (runner.bestMethod() == app.framex.core.optimization.StopMethod.AccessibilityForceStop) 15 else 40
            runner.run(apps.taskKillList(limit).map { it.packageName })
            local.update { it.copy(busy = false) }
        }
    }

    fun stopMethod() = runner.bestMethod()

    fun refreshCapabilities() = local.update { it.copy(capabilities = capabilities.detect()) }

    fun select(p: OptimizationProfile) { viewModelScope.launch { settings.setSelectedProfile(p) } }

    fun toggleCustom(a: OptAction) {
        viewModelScope.launch {
            val cur = settings.settings.first().customActions
            settings.setCustomActions(if (a in cur) cur - a else cur + a)
        }
    }

    /** Starts applying; asks for confirmation first if apps would be stopped. */
    fun requestApply(profile: OptimizationProfile) {
        viewModelScope.launch {
            val custom = settings.settings.first().customActions
            val actions = Profiles.actionsFor(profile, custom)
            val caps = capabilities.detect()
            if (OptAction.StopBackgroundApps in actions && caps[OptAction.StopBackgroundApps] == Capability.Supported) {
                if (settings.settings.first().autoStopBackground) {
                    // automatic: no questions, the never-stop list and the protected apps are respected
                    val list = apps.autoList(emptySet(), runner.suggestedLimit())
                    applyNow(profile, list.map { it.packageName })
                    return@launch
                }
                local.update { it.copy(busy = true, smart = null) }
                val list = apps.eligible()
                local.update { it.copy(busy = false, confirm = ConfirmStop(profile, list)) }
            } else applyNow(profile, emptyList())
        }
    }

    fun confirmStop(selected: List<String>) {
        val c = local.value.confirm ?: return
        local.update { it.copy(confirm = null) }
        viewModelScope.launch { applyNow(c.profile, selected) }
    }

    fun cancelDialogs() = local.update { it.copy(confirm = null, smart = null) }

    private suspend fun applyNow(profile: OptimizationProfile, stop: List<String>) {
        local.update { it.copy(busy = true, capabilities = capabilities.detect()) }
        val custom = settings.settings.first().customActions
        val report = engine.apply(profile, ApplyRequest(stopPackages = stop, customActions = custom))
        local.update { it.copy(busy = false, report = report) }
    }

    fun restore() {
        viewModelScope.launch {
            local.update { it.copy(busy = true) }
            val r = engine.restore()
            local.update { it.copy(busy = false, report = r) }
        }
    }

    fun smartBoost() {
        viewModelScope.launch {
            local.update { it.copy(busy = true) }
            val snap = source.snapshots(flowOf(1000L)).first()
            val temp = (snap.battery as? Metric.Available)?.value?.temperatureC
            val ram = (snap.ram as? Metric.Available)?.value?.usedFraction
            val rec = SmartBoost.recommend(temp, ram, shell.isAvailable())
            local.update { it.copy(busy = false, smart = rec) }
        }
    }

    fun acceptSmart() {
        val rec = local.value.smart ?: return
        local.update { it.copy(smart = null) }
        select(rec.profile)
        requestApply(rec.profile)
    }
}
