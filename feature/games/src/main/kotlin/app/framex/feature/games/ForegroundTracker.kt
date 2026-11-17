package app.framex.feature.games

enum class FgEvent { Resumed, Paused, Stopped }

/**
 * Which app is in front, from the system's activity events.
 * Windows are tracked one by one (package + class): an app with several screens (splash, login, the game itself)
 * must stay "in front" when ONE of its screens closes while another is already open.
 * Screens of the phone maker's own game booster are ignored, so they do not push the game out.
 */
class ForegroundTracker {
    private val resumed = LinkedHashMap<String, String>() // "pkg/class" -> pkg, in the order they came to the front

    val foreground: String? get() = resumed.values.lastOrNull()

    fun onEvent(type: FgEvent, pkg: String, cls: String?) {
        if (isHelper(pkg, cls)) return
        val key = "$pkg/${cls.orEmpty()}"
        when (type) {
            FgEvent.Resumed -> { resumed.remove(key); resumed[key] = pkg }
            FgEvent.Paused, FgEvent.Stopped -> resumed.remove(key)
        }
        while (resumed.size > MAX_TRACKED) resumed.remove(resumed.keys.first()) // an event we missed must not stick forever
    }

    fun clear() = resumed.clear()

    companion object {
        private const val MAX_TRACKED = 12
        private val BOOSTER_PACKAGES = setOf("com.miui.securitycenter", "com.oplus.games", "com.coloros.gamespaceui", "com.vivo.gamecube")

        /** A game booster's sidebar / loading screens, not an app the user opened. */
        fun isHelper(pkg: String, cls: String?) = pkg in BOOSTER_PACKAGES && cls?.lowercase()?.let { "game" in it || "turbo" in it } == true
    }
}
