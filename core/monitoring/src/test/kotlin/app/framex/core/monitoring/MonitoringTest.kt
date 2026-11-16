package app.framex.core.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonitoringTest {
    @Test fun ramUsedAndFraction() {
        val r = RamInfo(totalBytes = 4000, availableBytes = 1000, lowMemory = false)
        assertEquals(3000, r.usedBytes)
        assertEquals(0.75f, r.usedFraction, 0.0001f)
    }

    @Test fun ramZeroTotalDoesNotDivide() {
        assertEquals(0f, RamInfo(0, 0, false).usedFraction, 0f)
    }

    @Test fun cpuFirstSampleIsNull() {
        assertNull(CpuLoadCalculator().update("cpu  100 0 100 800 0 0 0 0"))
    }

    @Test fun cpuLoadFromDelta() {
        val c = CpuLoadCalculator()
        c.update("cpu  100 0 100 800 0 0 0 0")
        // +100 busy, +100 idle -> 50%
        assertEquals(0.5f, c.update("cpu  150 0 150 900 0 0 0 0")!!, 0.0001f)
    }

    @Test fun cpuGarbageIsNull() {
        assertNull(CpuLoadCalculator().update("garbage"))
    }
}
