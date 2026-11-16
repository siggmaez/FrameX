package app.framex.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.framex.core.common.OptAction
import androidx.datastore.preferences.preferencesDataStore
import app.framex.core.common.OptimizationProfile
import app.framex.core.common.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val monitoringIntervalMs: Long = 2000L,
    val selectedProfile: OptimizationProfile = OptimizationProfile.Balanced,
    val customActions: Set<OptAction> = setOf(OptAction.DoNotDisturb),
    /** When on, the Play button analyses the device and picks the profile; when off it uses the game's saved profile. */
    val autoAnalyzeOnPlay: Boolean = true,
    val fpsOverlayEnabled: Boolean = false,
    /** Play / Quick Boost stop eligible background apps by themselves, without asking each time. */
    val autoStopBackground: Boolean = true,
    /** Apps that are never stopped (messengers, music, ...). Editable in Settings. */
    val neverStopApps: Set<String> = DEFAULT_NEVER_STOP,
    val networkMode: app.framex.core.common.NetworkMode = app.framex.core.common.NetworkMode.WifiAndMobile,
)

internal val Context.settingsStore by preferencesDataStore(name = "framex_settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme_mode")
        val interval = longPreferencesKey("monitoring_interval_ms")
        val profile = stringPreferencesKey("selected_profile")
        val custom = stringSetPreferencesKey("custom_actions")
        val autoAnalyze = booleanPreferencesKey("auto_analyze_on_play")
        val overlay = booleanPreferencesKey("fps_overlay")
        val autoStop = booleanPreferencesKey("auto_stop_background")
        val neverStop = stringSetPreferencesKey("never_stop_apps")
        val netMode = stringPreferencesKey("network_mode")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { p ->
        AppSettings(
            themeMode = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System,
            monitoringIntervalMs = (p[Keys.interval] ?: 2000L).coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS),
            selectedProfile = p[Keys.profile]?.let { runCatching { OptimizationProfile.valueOf(it) }.getOrNull() }
                ?: OptimizationProfile.Balanced,
            customActions = p[Keys.custom]?.mapNotNull { runCatching { OptAction.valueOf(it) }.getOrNull() }?.toSet()
                ?: setOf(OptAction.DoNotDisturb),
            autoAnalyzeOnPlay = p[Keys.autoAnalyze] ?: true,
            fpsOverlayEnabled = p[Keys.overlay] ?: false,
            autoStopBackground = p[Keys.autoStop] ?: true,
            neverStopApps = p[Keys.neverStop] ?: DEFAULT_NEVER_STOP,
            networkMode = p[Keys.netMode]?.let { runCatching { app.framex.core.common.NetworkMode.valueOf(it) }.getOrNull() }
                ?: app.framex.core.common.NetworkMode.WifiAndMobile,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) { context.settingsStore.edit { it[Keys.theme] = mode.name } }
    suspend fun setSelectedProfile(p: OptimizationProfile) { context.settingsStore.edit { it[Keys.profile] = p.name } }
    suspend fun setCustomActions(a: Set<OptAction>) { context.settingsStore.edit { it[Keys.custom] = a.map { x -> x.name }.toSet() } }
    suspend fun setAutoAnalyzeOnPlay(on: Boolean) { context.settingsStore.edit { it[Keys.autoAnalyze] = on } }
    suspend fun setNetworkMode(m: app.framex.core.common.NetworkMode) { context.settingsStore.edit { it[Keys.netMode] = m.name } }
    suspend fun setAutoStopBackground(on: Boolean) { context.settingsStore.edit { it[Keys.autoStop] = on } }
    suspend fun setNeverStopApps(s: Set<String>) { context.settingsStore.edit { it[Keys.neverStop] = s } }
    suspend fun setFpsOverlay(on: Boolean) { context.settingsStore.edit { it[Keys.overlay] = on } }
    suspend fun setMonitoringInterval(ms: Long) {
        context.settingsStore.edit { it[Keys.interval] = ms.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS) }
    }

    companion object {
        const val MIN_INTERVAL_MS = 1000L
        const val MAX_INTERVAL_MS = 10000L
    }
}

/** Pre-protected so a boost never silences the apps people usually keep for messages and music. Only matters if installed. */
val DEFAULT_NEVER_STOP: Set<String> = setOf(
    "com.whatsapp", "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram",
    "com.viber.voip", "com.discord", "org.signal.messenger", "com.spotify.music", "com.google.android.apps.messaging",
)
