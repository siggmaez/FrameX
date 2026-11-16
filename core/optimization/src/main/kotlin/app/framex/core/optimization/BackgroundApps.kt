package app.framex.core.optimization

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class BackgroundApp(val packageName: String, val label: String)

/**
 * Apps FrameX may offer to stop: user-installed, not force-stopped already, and never
 * FrameX itself, the launcher, keyboards, accessibility services, the default dialer/SMS app,
 * or the game being launched. System apps are never offered.
 */
@Singleton
class BackgroundAppsProvider @Inject constructor(@ApplicationContext private val context: Context) {

    suspend fun eligible(excluding: Set<String> = emptySet()): List<BackgroundApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val protected = protectedPackages() + excluding + context.packageName
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .filter { it.flags and ApplicationInfo.FLAG_STOPPED == 0 }
            .filter { it.enabled && it.packageName !in protected }
            .map { BackgroundApp(it.packageName, pm.getApplicationLabel(it).toString()) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    private fun protectedPackages(): Set<String> {
        val pm = context.packageManager
        val out = HashSet<String>()
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).forEach { out += it.activityInfo.packageName }
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')?.let { out += it }
        (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).forEach { out += it.resolveInfo.serviceInfo.packageName }
        (context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage?.let { out += it }
        Telephony.Sms.getDefaultSmsPackage(context)?.let { out += it }
        return out
    }
}
