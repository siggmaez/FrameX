package app.framex.feature.games

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.framex.core.common.OptimizationProfile
import app.framex.core.database.GameProfileEntity
import app.framex.core.database.GameSessionEntity
import app.framex.core.database.GamesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GamesUiState(
    val tracked: List<GameProfileEntity> = emptyList(),
    val installed: List<InstalledApp>? = null,
    val showAll: Boolean = false,
    val sessions: List<GameSessionEntity> = emptyList(),
    val watching: Boolean = false,
    val hasUsageAccess: Boolean = false,
)

@HiltViewModel
class GamesViewModel @Inject constructor(
    private val repo: GamesRepository,
    private val discovery: GameDiscovery,
    private val watcher: WatcherController,
) : ViewModel() {
    private val installed = MutableStateFlow<List<InstalledApp>?>(null)
    private val showAll = MutableStateFlow(false)
    private val usage = MutableStateFlow(watcher.hasUsageAccess())

    val state: StateFlow<GamesUiState> = combine(
        repo.profiles(), installed, showAll, repo.sessions(30), watcher.state.running, usage,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        GamesUiState(values[0] as List<GameProfileEntity>, values[1] as List<InstalledApp>?, values[2] as Boolean,
            values[3] as List<GameSessionEntity>, values[4] as Boolean, values[5] as Boolean)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GamesUiState())

    init { viewModelScope.launch { installed.value = discovery.installed() } }

    fun refreshAccess() { usage.value = watcher.hasUsageAccess() }
    fun setShowAll(v: Boolean) = showAll.update { v }
    fun setWatching(on: Boolean) { if (on) watcher.start() else watcher.stop() }

    fun add(app: InstalledApp) {
        viewModelScope.launch { repo.save(GameProfileEntity(app.packageName, app.label, OptimizationProfile.Gaming.name, true)) }
    }
    fun remove(pkg: String) { viewModelScope.launch { repo.remove(pkg) } }
    fun setProfile(g: GameProfileEntity, p: OptimizationProfile) { viewModelScope.launch { repo.save(g.copy(profile = p.name)) } }
    fun setAuto(g: GameProfileEntity, on: Boolean) { viewModelScope.launch { repo.save(g.copy(autoApply = on)) } }
    fun clearSessions() { viewModelScope.launch { repo.clearSessions() } }
}
