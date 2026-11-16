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
    val lastSession: app.framex.core.database.GameSessionEntity? = null,
    val shizuku: app.framex.core.common.ShizukuState = app.framex.core.common.ShizukuState.NotInstalled,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    settings: SettingsRepository,
    source: SystemMetricsSource,
    store: app.framex.core.datastore.EngineStateStore,
    games: app.framex.core.database.GamesRepository,
    shizuku: app.framex.core.shizuku.ShizukuManager,
) : ViewModel() {
    /** Upstream polling runs only while the screen is collected (WhileSubscribed). */
    val state: StateFlow<HomeUiState> = combine(
        source.snapshots(settings.settings.map { it.monitoringIntervalMs }),
        settings.settings,
        store.snapshot,
        games.sessions(1),
        shizuku.status,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        HomeUiState(values[0] as SystemSnapshot?, (values[1] as app.framex.core.datastore.AppSettings).selectedProfile,
            values[2] as app.framex.core.datastore.EngineSnapshot?, (values[3] as List<app.framex.core.database.GameSessionEntity>).firstOrNull(),
            (values[4] as app.framex.core.shizuku.ShizukuStatus).state)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())
}
