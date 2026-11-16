package app.framex.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.framex.core.common.OptimizationProfile
import app.framex.core.datastore.SettingsRepository
import app.framex.core.monitoring.SystemMetricsSource
import app.framex.core.monitoring.SystemSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class HomeUiState(
    val snapshot: SystemSnapshot? = null,
    val profile: OptimizationProfile = OptimizationProfile.Balanced,
    val active: app.framex.core.datastore.EngineSnapshot? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    settings: SettingsRepository,
    source: SystemMetricsSource,
    store: app.framex.core.datastore.EngineStateStore,
) : ViewModel() {
    /** Upstream polling runs only while the screen is collected (WhileSubscribed). */
    val state: StateFlow<HomeUiState> = combine(
        source.snapshots(settings.settings.map { it.monitoringIntervalMs }),
        settings.settings,
        store.snapshot,
    ) { snap, s, active -> HomeUiState(snap, s.selectedProfile, active) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())
}
