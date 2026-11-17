package app.framex.feature.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundTrackerTest {
    @Test fun gameStaysInFrontWhenItsSplashScreenCloses() {
        val t = ForegroundTracker()
        t.onEvent(FgEvent.Resumed, "com.g.a", "com.g.Splash")
        t.onEvent(FgEvent.Resumed, "com.g.a", "com.g.Game")
        t.onEvent(FgEvent.Paused, "com.g.a", "com.g.Splash")   // the old screen closes after the new one opened
        t.onEvent(FgEvent.Stopped, "com.g.a", "com.g.Splash")
        assertEquals("com.g.a", t.foreground)                   // this is the case that used to hide the overlay
    }

    @Test fun leavingTheGameShowsTheOtherApp() {
        val t = ForegroundTracker()
        t.onEvent(FgEvent.Resumed, "com.g.a", "com.g.Game")
        t.onEvent(FgEvent.Paused, "com.g.a", "com.g.Game")
        t.onEvent(FgEvent.Resumed, "com.x.b", "com.x.Main")
        assertEquals("com.x.b", t.foreground)
    }

    @Test fun nothingOpenMeansNull() {
        val t = ForegroundTracker()
        t.onEvent(FgEvent.Resumed, "com.g.a", "A"); t.onEvent(FgEvent.Paused, "com.g.a", "A")
        assertNull(t.foreground)
    }

    @Test fun replayedEventsGiveTheSameResult() {
        val t = ForegroundTracker()
        repeat(2) {
            t.onEvent(FgEvent.Resumed, "com.g.a", "S"); t.onEvent(FgEvent.Resumed, "com.g.a", "G"); t.onEvent(FgEvent.Paused, "com.g.a", "S")
        }
        assertEquals("com.g.a", t.foreground)
    }

    @Test fun gameBoosterScreensDoNotPushTheGameOut() {
        val t = ForegroundTracker()
        t.onEvent(FgEvent.Resumed, "com.supercell.brawlstars", "com.supercell.titan.GameApp")
        t.onEvent(FgEvent.Resumed, "com.miui.securitycenter", "com.miui.gamebooster.ui.SideBarActivity")
        assertEquals("com.supercell.brawlstars", t.foreground)
        t.onEvent(FgEvent.Resumed, "com.miui.securitycenter", "com.miui.permcenter.MainActivity") // a real Security screen is a real app
        assertEquals("com.miui.securitycenter", t.foreground)
    }
}
