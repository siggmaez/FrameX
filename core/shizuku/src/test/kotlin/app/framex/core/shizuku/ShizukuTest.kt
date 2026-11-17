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

    @Test fun forceStopManyIsValidatedAndBatched() {
        val c = Commands.forceStopMany(listOf("com.a.b", "com.c.d"))!!
        assertTrue(c.contains("com.a.b com.c.d") && c.contains("am force-stop") && c.contains("wait"))
        assertNull(Commands.forceStopMany(emptyList()))
        assertNull(Commands.forceStopMany(listOf("com.a.b", "bad; reboot")))
        assertNull(Commands.forceStopMany(List(61) { "com.x.p$it".replace("p", "q") }))
    }

    @Test fun forceStopIsValidated() {
        assertEquals("am force-stop com.g.a", Commands.forceStop("com.g.a"))
        assertNull(Commands.forceStop("com.g.a; reboot"))
        assertNull(Commands.forceStop(""))
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

    @Test fun downscaleCommandFormatsAndValidates() {
        assertEquals("0.7", Commands.formatScale(0.7f))
        assertEquals("0.85", Commands.formatScale(0.85f))
        assertEquals("0.9", Commands.formatScale(1.0f))   // clamped
        assertEquals("0.3", Commands.formatScale(0.1f))
        assertEquals("cmd game set --mode performance --downscale 0.8 com.g.a", Commands.setDownscale("com.g.a", 0.8f))
        assertNull(Commands.setDownscale("com.g.a; reboot", 0.8f))
        assertEquals("cmd game reset com.g.a", Commands.resetGameConfig("com.g.a"))
    }

    @Test fun refreshRateCommands() {
        assertEquals("settings put system min_refresh_rate 90; settings put system peak_refresh_rate 90", Commands.setRefreshRate(90))
        assertNull(Commands.setRefreshRate(5)); assertNull(Commands.setRefreshRate(500))
        assertEquals("settings delete system min_refresh_rate; settings put system peak_refresh_rate 60.0", Commands.restoreRefreshRate("null", "60.0"))
        assertNull(Commands.restoreRefreshRate("90; reboot", "null"))
        assertEquals("90.0" to "null", Parsers.refreshSettings("90.0\nnull\n"))
        assertNull(Parsers.refreshSettings("90.0"))
        assertEquals("cmd power set-fixed-performance-mode-enabled true", Commands.setFixedPerformance(true))
    }

    @Test fun timeStatsCommands() {
        assertEquals("dumpsys SurfaceFlinger --timestats -enable", Commands.TIMESTATS_ENABLE)
        assertTrue(Commands.TIMESTATS_DUMP_CLEAR.contains("-dump") && Commands.TIMESTATS_DUMP_CLEAR.contains("-clear"))
    }

    @Test fun networkCommandsAndParsing() {
        assertEquals("cmd wifi force-hi-perf-mode enabled; cmd wifi force-low-latency-mode enabled", Commands.setWifiPerf(true))
        assertEquals("cmd wifi force-hi-perf-mode disabled; cmd wifi force-low-latency-mode disabled", Commands.setWifiPerf(false))
        assertEquals("cmd netpolicy set restrict-background true", Commands.setDataSaver(true))
        assertEquals(true, Parsers.dataSaverOn("Restrict background status: enabled"))
        assertEquals(false, Parsers.dataSaverOn("Restrict background status: disabled\n"))
        assertEquals(false, Parsers.dataSaverOn("Restrict background status: whitelisted"))
        assertNull(Parsers.dataSaverOn("something else"))
    }
}
