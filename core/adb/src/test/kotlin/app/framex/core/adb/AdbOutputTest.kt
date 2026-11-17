package app.framex.core.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbOutputTest {
    @Test fun wrapAppendsExitMarker() {
        assertEquals("am force-stop a.b; echo __FX_EXIT:\$?", AdbOutput.wrap("am force-stop a.b"))
    }

    @Test fun parseSuccess() {
        val r = AdbOutput.parse("some output\n__FX_EXIT:0\n")
        assertTrue(r.ok); assertEquals("some output", r.output)
    }

    @Test fun parseFailureKeepsOutput() {
        val r = AdbOutput.parse("Error: nope\n__FX_EXIT:1")
        assertFalse(r.ok); assertEquals("Error: nope", r.output)
    }

    @Test fun missingMarkerIsAFailure() {
        assertFalse(AdbOutput.parse("connection reset").ok)
    }

    @Test fun markerInsideOutputDoesNotFoolParser() {
        val r = AdbOutput.parse("__FX_EXIT:0 fake\nreal\n__FX_EXIT:2")
        assertFalse(r.ok)
    }
}
