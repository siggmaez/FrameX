package app.framex.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.framex.core.common.OptAction
import app.framex.core.common.OptimizationProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** What is currently applied and the previous values needed to restore it. */
data class EngineSnapshot(
    val profile: OptimizationProfile,
    val appliedAtMs: Long,
    /** Previous-state token per applied action (null when the action cannot be restored). */
    val tokens: Map<OptAction, String?>,
    val targetPackage: String? = null,
)

@Singleton
class EngineStateStore @Inject constructor(@ApplicationContext private val context: Context) {
    private object Keys {
        val profile = stringPreferencesKey("engine_profile")
        val at = longPreferencesKey("engine_applied_at")
        val target = stringPreferencesKey("engine_target")
        val tokens = stringSetPreferencesKey("engine_tokens")
    }

    val snapshot: Flow<EngineSnapshot?> = context.settingsStore.data.map { decode(it[Keys.profile], it[Keys.at], it[Keys.target], it[Keys.tokens]) }

    suspend fun load(): EngineSnapshot? = snapshot.first()

    suspend fun save(s: EngineSnapshot) {
        context.settingsStore.edit {
            it[Keys.profile] = s.profile.name
            it[Keys.at] = s.appliedAtMs
            if (s.targetPackage != null) it[Keys.target] = s.targetPackage else it.remove(Keys.target)
            it[Keys.tokens] = s.tokens.map { (a, t) -> "${a.name}=${t ?: ""}" }.toSet()
        }
    }

    suspend fun clear() {
        context.settingsStore.edit { it.remove(Keys.profile); it.remove(Keys.at); it.remove(Keys.target); it.remove(Keys.tokens) }
    }

    companion object {
        internal fun decode(profile: String?, at: Long?, target: String?, tokens: Set<String>?): EngineSnapshot? {
            val p = profile?.let { runCatching { OptimizationProfile.valueOf(it) }.getOrNull() } ?: return null
            val map = (tokens ?: emptySet()).mapNotNull { e ->
                val i = e.indexOf('=')
                if (i < 0) return@mapNotNull null
                val a = runCatching { OptAction.valueOf(e.substring(0, i)) }.getOrNull() ?: return@mapNotNull null
                a to e.substring(i + 1).ifEmpty { null }
            }.toMap()
            return EngineSnapshot(p, at ?: 0L, map, target)
        }
    }
}
