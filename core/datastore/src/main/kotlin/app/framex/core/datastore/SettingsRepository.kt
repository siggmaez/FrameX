package app.framex.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
)

private val Context.settingsStore by preferencesDataStore(name = "framex_settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme_mode")
        val interval = longPreferencesKey("monitoring_interval_ms")
        val profile = stringPreferencesKey("selected_profile")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { p ->
        AppSettings(
            themeMode = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System,
            monitoringIntervalMs = (p[Keys.interval] ?: 2000L).coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS),
            selectedProfile = p[Keys.profile]?.let { runCatching { OptimizationProfile.valueOf(it) }.getOrNull() }
                ?: OptimizationProfile.Balanced,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) { context.settingsStore.edit { it[Keys.theme] = mode.name } }
    suspend fun setMonitoringInterval(ms: Long) {
        context.settingsStore.edit { it[Keys.interval] = ms.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS) }
    }

    companion object {
        const val MIN_INTERVAL_MS = 1000L
        const val MAX_INTERVAL_MS = 10000L
    }
}
