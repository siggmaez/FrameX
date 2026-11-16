package app.framex.feature.games

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class InstalledApp(val packageName: String, val label: String, val isGame: Boolean)

@Singleton
class GameDiscovery @Inject constructor(@ApplicationContext private val context: Context) {
    /** Launchable, non-system apps. [isGame] comes from the category/flag the app declares itself. */
    suspend fun installed(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        pm.getInstalledApplications(0)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0 }
            .filter { it.packageName != context.packageName }
            .map { InstalledApp(it.packageName, pm.getApplicationLabel(it).toString(), isGame(it)) }
            .sortedBy { it.label.lowercase() }
    }

    @Suppress("DEPRECATION")
    private fun isGame(i: ApplicationInfo) = i.category == ApplicationInfo.CATEGORY_GAME || i.flags and ApplicationInfo.FLAG_IS_GAME != 0
}
