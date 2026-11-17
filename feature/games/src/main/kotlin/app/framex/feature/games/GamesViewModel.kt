package app.framex.feature.games

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.framex.core.common.OptAction
import app.framex.core.common.OptimizationProfile
import app.framex.core.database.GameProfileEntity
import app.framex.core.database.GameSessionEntity
import app.framex.core.database.GamesRepository
import app.framex.core.datastore.SettingsRepository
import app.framex.core.optimization.BackgroundApp
import app.framex.core.optimization.BackgroundAppsProvider
import app.framex.core.optimization.FindingsReader
import app.framex.core.optimization.GamePlanner
import app.framex.core.optimization.LaunchPlan
import app.framex.core.optimization.PrivilegedShell
import app.framex.core.optimization.Profiles
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where the Play flow currently waits for the user. */
sealed interface PlayStep {
    data class Advice(val game: GameProfileEntity, val plan: LaunchPlan) : PlayStep
    data class ConfirmStop(val game: GameProfileEntity, val profile: OptimizationProfile, val apps: List<BackgroundApp>) : PlayStep
}

enum class PlayNotice { NoUsageAccess, CannotLaunch }

data class GamesUiState(
    val tracked: List<GameProfileEntity> = emptyList(),
    val installed: List<InstalledApp>? = null,
    val showAll: Boolean = false,
    val sessions: List<GameSessionEntity> = emptyList(),
    val watching: Boolean = false,
    val hasUsageAccess: Boolean = false,
    val play: PlayStep? = null,
    val notice: PlayNotice? = null,
    /** Set when the UI must show the system screen-capture dialog before starting this game. */
    val captureFor: GameProfileEntity? = null,
)

@HiltViewModel
class GamesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: GamesRepository,
    private val discovery: GameDiscovery,
    private val watcher: WatcherController,
    private val settings: SettingsRepository,
    private val findings: FindingsReader,
    private val apps: BackgroundAppsProvider,
    private val shell: PrivilegedShell,
    private val capture: CaptureFpsController,
    private val runner: app.framex.core.optimization.BoostRunner,
    private val adb: app.framex.core.adb.AdbController,
) : ViewModel() {
    private val installed = MutableStateFlow<List<InstalledApp>?>(null)
    private val showAll = MutableStateFlow(false)
    private val usage = MutableStateFlow(watcher.hasUsageAccess())
    private val play = MutableStateFlow<PlayStep?>(null)
    private val notice = MutableStateFlow<PlayNotice?>(null)
    private val captureFor = MutableStateFlow<GameProfileEntity?>(null)

    val state: StateFlow<GamesUiState> = combine(
        repo.profiles(), installed, showAll, repo.sessions(30), watcher.state.running, usage, play, notice, captureFor,
    ) { v ->
        @Suppress("UNCHECKED_CAST")
        GamesUiState(v[0] as List<GameProfileEntity>, v[1] as List<InstalledApp>?, v[2] as Boolean,
            v[3] as List<GameSessionEntity>, v[4] as Boolean, v[5] as Boolean, v[6] as PlayStep?, v[7] as PlayNotice?, v[8] as GameProfileEntity?)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GamesUiState())

    val autoAnalyze: StateFlow<Boolean> = settings.settings.map { it.autoAnalyzeOnPlay }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

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
    fun setBoostLevel(g: GameProfileEntity, level: app.framex.core.optimization.BoostLevel) { viewModelScope.launch { repo.save(g.copy(boostLevel = level.name)) } }
    fun forgetStopList(g: GameProfileEntity) { viewModelScope.launch { repo.save(g.copy(stopPackages = "")) } }
    fun clearSessions() { viewModelScope.launch { repo.clearSessions() } }
    fun dismissNotice() { notice.value = null }

    // ---------------- Play ----------------

    /** One tap: analyse the device (unless turned off in Settings), pick the profile, arm it, and start the game. */
    fun play(g: GameProfileEntity) {
        viewModelScope.launch {
            notice.value = null
            val s = settings.settings.first()
            if (!watcher.hasUsageAccess()) {
                // Without it FrameX could not put settings back afterwards, so it changes nothing.
                notice.value = if (launchGame(g.packageName)) PlayNotice.NoUsageAccess else PlayNotice.CannotLaunch
                return@launch
            }
            // No Shizuku: the overlay can still show an ESTIMATED FPS if the user accepts screen capture for this session.
            if (s.fpsOverlayEnabled && s.fpsCaptureEstimate && android.provider.Settings.canDrawOverlays(context) && !shell.isAvailable() && !capture.running.value) {
                captureFor.value = g
                return@launch
            }
            continuePlay(g)
        }
    }

    /** Result of the system screen-capture dialog (the UI launched it because [GamesUiState.captureFor] was set). */
    fun onCaptureResult(resultCode: Int, data: Intent?) {
        val g = captureFor.value ?: return
        captureFor.value = null
        if (resultCode == android.app.Activity.RESULT_OK && data != null) capture.start(resultCode, data)
        viewModelScope.launch { continuePlay(g) } // declined: play anyway, FPS stays n/a
    }

    private suspend fun continuePlay(g: GameProfileEntity) {
        run {
            val s = settings.settings.first()
            val plan = if (s.autoAnalyzeOnPlay) GamePlanner.plan(findings.read()) else LaunchPlan(profileOf(g), emptyList())
            if (plan.advisories.any { it.important }) play.value = PlayStep.Advice(g, plan)
            else proceed(g, plan.profile, s.customActions)
        }
    }

    fun continueAfterAdvice() {
        val step = play.value as? PlayStep.Advice ?: return
        play.value = null
        viewModelScope.launch { proceed(step.game, step.plan.profile, settings.settings.first().customActions) }
    }

    fun confirmStop(selected: List<String>, remember: Boolean) {
        val step = play.value as? PlayStep.ConfirmStop ?: return
        play.value = null
        viewModelScope.launch {
            if (remember) repo.save(step.game.copy(stopPackages = selected.joinToString(",")))
            armAndLaunch(step.game, step.profile, selected)
        }
    }

    fun cancelPlay() { play.value = null }

    private suspend fun proceed(g: GameProfileEntity, profile: OptimizationProfile, custom: Set<OptAction>) {
        val wantsStop = OptAction.StopBackgroundApps in Profiles.actionsFor(profile, custom)
        val remembered = g.stopPackages.split(',').filter { it.isNotBlank() }
        when {
            !wantsStop -> armAndLaunch(g, profile, emptyList())
            // automatic: no questions; protected apps and the never-stop list are respected
            settings.settings.first().autoStopBackground ->
                armAndLaunch(g, profile, apps.autoList(setOf(g.packageName), runner.suggestedLimit()).map { it.packageName })
            remembered.isNotEmpty() -> armAndLaunch(g, profile, remembered)
            else -> play.value = PlayStep.ConfirmStop(g, profile, apps.eligible(excluding = setOf(g.packageName)))
        }
    }

    /** Stops the apps BEFORE the game opens (so they do not compete while it loads), then starts it. */
    private suspend fun armAndLaunch(g: GameProfileEntity, profile: OptimizationProfile, stop: List<String>) {
        if (stop.isNotEmpty()) {
            runner.run(stop)
            kotlinx.coroutines.delay(1500) // let the result be read
            runner.dismiss()
        }
        if (profile != OptimizationProfile.Balanced) {
            watcher.state.arm(g.packageName, ArmedLaunch(profile, emptyList())) // stopping is already done
            if (!watcher.state.running.value) watcher.start()
        }
        if (!launchGame(g.packageName)) notice.value = PlayNotice.CannotLaunch
    }

    private fun launchGame(pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    private fun profileOf(g: GameProfileEntity) = runCatching { OptimizationProfile.valueOf(g.profile) }.getOrDefault(OptimizationProfile.Gaming)

    /** Settings shortcuts offered by the advisories. */
    fun openBatterySaverSettings() = openSettings(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS)
    fun openDisplaySettings() = openSettings(android.provider.Settings.ACTION_DISPLAY_SETTINGS)
    private fun openSettings(action: String) {
        runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

}
