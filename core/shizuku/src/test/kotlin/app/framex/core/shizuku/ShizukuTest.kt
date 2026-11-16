package app.framex.core.shizuku

import app.framex.core.common.ShizukuState
import app.framex.core.optimization.GameModeLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuTest {
    private fun r(installed: Boolean = true, alive: Boolean = true, v: Int? = 13, granted: Boolean = false, denied: Boolean = false) =
        ShizukuStateResolver.resolve(installed, alive, v, granted, denied)

    @Test fun notInstalledAndNotRunning() {
        assertEquals(ShizukuState.NotInstalled, r(installed = false, alive = false, v = null))
        assertEquals(ShizukuState.NotRunning, r(installed = true, alive = false, v = null))
    }

    @Test fun runningWithoutAppInstalledIsStillUsable() {
        assertEquals(ShizukuState.Ready, r(installed = false, granted = true))
    }

    @Test fun versionCheck() {
        assertEquals(ShizukuState.Outdated, r(v = 10, granted = true))
        assertEquals(ShizukuState.Outdated, r(v = null, granted = true))
        assertEquals(ShizukuState.Ready, r(v = 11, granted = true))
    }

    @Test fun permissionStates() {
        assertEquals(ShizukuState.NeedsPermission, r())
        assertEquals(ShizukuState.PermissionDenied, r(denied = true))
        assertEquals(ShizukuState.Ready, r(granted = true, denied = true))
    }

    @Test fun binderDeathMeansNotRunning() {
        // after the binder dies the manager re-resolves with alive=false
        assertEquals(ShizukuState.NotRunning, r(alive = false, v = null, granted = true))
    }

    @Test fun packageValidation() {
        assertTrue(Commands.isValidPackage("com.example.game"))
        assertFalse(Commands.isValidPackage("com.example; rm -rf /"))
        assertFalse(Commands.isValidPackage("nodots"))
        assertFalse(Commands.isValidPackage("com.x\nid"))
        assertNull(Commands.setGameMode("bad pkg", GameModeLevel.Performance))
        assertEquals("cmd game mode performance com.g.a", Commands.setGameMode("com.g.a", GameModeLevel.Performance))
    }

    @Test fun latencyQuotingIsSafe() {
        assertEquals("dumpsys SurfaceFlinger --latency 'SurfaceView[a.b/a.b.C]@0(BLAST)#1'", Commands.latency("SurfaceView[a.b/a.b.C]@0(BLAST)#1"))
        assertNull(Commands.latency("x'; reboot; '"))
        assertNull(Commands.latency("a\nb"))
        assertNull(Commands.latency(""))
    }

    @Test fun batterySaverCommands() {
        assertEquals("settings put global low_power 1", Commands.setBatterySaver(true))
        assertEquals(true, Parsers.batterySaverOn("1\n"))
        assertEquals(false, Parsers.batterySaverOn("0"))
        assertNull(Parsers.batterySaverOn("null"))
    }

    @Test fun gameModeParserReturnsActiveNotAvailable() {
        assertEquals("performance", Parsers.currentGameMode("com.g current mode: performance, available modes: [standard, performance, battery]"))
        assertEquals("standard", Parsers.currentGameMode("Current game mode: STANDARD"))
        assertNull(Parsers.currentGameMode("available modes: [standard, performance]"))
    }
}
