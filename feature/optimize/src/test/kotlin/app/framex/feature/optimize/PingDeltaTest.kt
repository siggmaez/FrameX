package app.framex.feature.optimize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PingDeltaTest {
    @Test fun lowerPingIsNegative() {
        assertEquals(-20f, PingDelta.percent(50f, 40f)!!, 0.001f)
        assertEquals(10f, PingDelta.percent(50f, 55f)!!, 0.001f)
    }

    @Test fun unknownOrZeroGivesNothing() {
        assertNull(PingDelta.percent(null, 40f)); assertNull(PingDelta.percent(50f, null)); assertNull(PingDelta.percent(0f, 10f))
    }
}
