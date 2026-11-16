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
    private val repo: SettingsRepository,
    private val history: app.framex.core.database.MetricHistoryRepository,
    private val shizukuManager: app.framex.core.shizuku.ShizukuManager,
) : ViewModel() {
    val settings: StateFlow<AppSettings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setTheme(mode: ThemeMode) { viewModelScope.launch { repo.setThemeMode(mode) } }
    val shizuku: StateFlow<app.framex.core.shizuku.ShizukuStatus> = shizukuManager.status
    fun refreshShizuku() = shizukuManager.refresh()
    fun requestShizuku() = shizukuManager.requestPermission()
    fun clearHistory() { viewModelScope.launch { history.clear() } }
    fun setInterval(ms: Long) { viewModelScope.launch { repo.setMonitoringInterval(ms) } }
}
