package app.framex.feature.games

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorToolsTest {
    @Test fun acceptsExportedEnabledGameScreens() {
        assertTrue(VendorGameTools.isCandidate("com.miui.gamebooster.ui.GameBoosterRealMainActivity", exported = true, enabled = true))
        assertTrue(VendorGameTools.isCandidate("com.x.TurboSettingsActivity", exported = true, enabled = true))
    }

    @Test fun rejectsHiddenDisabledOrUnrelatedScreens() {
        assertFalse(VendorGameTools.isCandidate("com.miui.gamebooster.ui.Hidden", exported = false, enabled = true))
        assertFalse(VendorGameTools.isCandidate("com.miui.gamebooster.ui.Off", exported = true, enabled = false))
        assertFalse(VendorGameTools.isCandidate("com.miui.cleaner.CleanActivity", exported = true, enabled = true))
    }
}
