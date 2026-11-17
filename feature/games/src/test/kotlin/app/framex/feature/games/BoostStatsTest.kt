package app.framex.feature.games

import app.framex.core.database.GameSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoostStatsTest {
    private fun s(fps: Float?, level: String?, src: String? = "SurfaceFlinger") =
        GameSessionEntity(packageName = "a.b", label = "G", startMs = 0, avgFps = fps, boostLevel = level, fpsSource = src)

    @Test fun separatesBoostedFromPlainSessions() {
        val c = BoostStats.compare(listOf(s(60f, "Strong"), s(50f, "Max"), s(40f, "Off"), s(44f, null), s(null, "Strong")))
        assertEquals(55f, c.boostedAvg!!, 0.001f); assertEquals(2, c.boostedSessions)
        assertEquals(42f, c.plainAvg!!, 0.001f); assertEquals(2, c.plainSessions)
        assertTrue(!c.estimated)
    }

    @Test fun missingGroupsAreNullNotZero() {
        val c = BoostStats.compare(listOf(s(60f, "Safe")))
        assertNull(c.plainAvg); assertEquals(0, c.plainSessions)
        assertNull(BoostStats.compare(emptyList()).boostedAvg)
    }

    @Test fun flagsEstimates() {
        assertTrue(BoostStats.compare(listOf(s(30f, "Safe", "ScreenCapture"))).estimated)
    }
}
