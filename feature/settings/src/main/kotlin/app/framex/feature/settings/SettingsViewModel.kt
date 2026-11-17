package app.framex.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.framex.core.common.ThemeMode
import app.framex.core.datastore.AppSettings
import app.framex.core.datastore.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val repo: SettingsRepository,
    private val history: app.framex.core.database.MetricHistoryRepository,
    private val shizukuManager: app.framex.core.shizuku.ShizukuManager,
    private val runner: app.framex.core.optimization.BoostRunner,
    private val appsProvider: app.framex.core.optimization.BackgroundAppsProvider,
) : ViewModel() {
    val settings: StateFlow<AppSettings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setTheme(mode: ThemeMode) { viewModelScope.launch { repo.setThemeMode(mode) } }
    val shizuku: StateFlow<app.framex.core.shizuku.ShizukuStatus> = shizukuManager.status
    fun refreshShizuku() = shizukuManager.refresh()
    fun requestShizuku() = shizukuManager.requestPermission()
    fun setAutoAnalyze(on: Boolean) { viewModelScope.launch { repo.setAutoAnalyzeOnPlay(on) } }
    fun setNetworkMode(m: app.framex.core.common.NetworkMode) { viewModelScope.launch { repo.setNetworkMode(m) } }
    fun setCaptureEstimate(on: Boolean) { viewModelScope.launch { repo.setFpsCaptureEstimate(on) } }
    fun setOverlayStyle(s: app.framex.core.common.OverlayStyle) { viewModelScope.launch { repo.setOverlayStyle(s) } }
    fun setOverlay(on: Boolean) { viewModelScope.launch { repo.setFpsOverlay(on) } }
    fun setAutoStop(on: Boolean) { viewModelScope.launch { repo.setAutoStopBackground(on) } }
    fun setNeverStop(s: Set<String>) { viewModelScope.launch { repo.setNeverStopApps(s) } }
    fun stopMethod() = runner.bestMethod()
    fun a11yBundled() = app.framex.core.optimization.AutomationHolder.isBundled(appContext)
    suspend fun userApps() = appsProvider.userApps()
    fun clearHistory() { viewModelScope.launch { history.clear() } }
    fun setInterval(ms: Long) { viewModelScope.launch { repo.setMonitoringInterval(ms) } }
}
