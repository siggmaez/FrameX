package app.framex.core.datastore

import app.framex.core.common.OptAction
import app.framex.core.common.OptimizationProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineStateStoreTest {
    @Test fun nothingAppliedDecodesToNull() {
        assertNull(EngineStateStore.decode(null, null, null, null))
    }

    @Test fun corruptProfileDecodesToNull() {
        assertNull(EngineStateStore.decode("NotAProfile", 1L, null, emptySet()))
    }

    @Test fun tokensRoundTripIncludingNullAndPipes() {
        val s = EngineStateStore.decode("Performance", 99L, "com.g.a",
            setOf("DoNotDisturb=2", "StopBackgroundApps=", "GameModePerformance=com.g.a|standard", "Bogus=1", "garbage"))!!
        assertEquals(OptimizationProfile.Performance, s.profile)
        assertEquals(99L, s.appliedAtMs); assertEquals("com.g.a", s.targetPackage)
        assertEquals("2", s.tokens[OptAction.DoNotDisturb])
        assertNull(s.tokens[OptAction.StopBackgroundApps]); assertEquals(true, OptAction.StopBackgroundApps in s.tokens)
        assertEquals("com.g.a|standard", s.tokens[OptAction.GameModePerformance])
        assertEquals(3, s.tokens.size) // unknown actions and malformed entries are dropped
    }
}
