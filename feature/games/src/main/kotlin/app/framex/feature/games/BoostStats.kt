package app.framex.feature.games

import app.framex.core.database.GameSessionEntity

/** Average FPS of one game's sessions with a boost applied versus without, from measured/estimated session FPS only. */
data class BoostCompare(val boostedAvg: Float?, val boostedSessions: Int, val plainAvg: Float?, val plainSessions: Int, val estimated: Boolean)

object BoostStats {
    fun compare(sessions: List<GameSessionEntity>): BoostCompare {
        val withFps = sessions.filter { it.avgFps != null }
        val (boosted, plain) = withFps.partition { it.boostLevel != null && it.boostLevel != "Off" }
        fun avg(l: List<GameSessionEntity>) = if (l.isEmpty()) null else l.map { it.avgFps!! }.average().toFloat()
        return BoostCompare(avg(boosted), boosted.size, avg(plain), plain.size, withFps.any { it.fpsSource == "ScreenCapture" })
    }
}
