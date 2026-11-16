package app.framex.core.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    @Test fun pingStatsBasic() {
        val s = PingStats.from(listOf(10f, 20f, null, 10f))
        assertEquals(4, s.sent); assertEquals(3, s.received)
        assertEquals(25f, s.lossPercent, 0.001f)
        assertEquals(10f, s.minMs!!, 0f); assertEquals(20f, s.maxMs!!, 0f)
        assertEquals(13.333f, s.avgMs!!, 0.01f)
        assertEquals(10f, s.jitterMs!!, 0.001f) // |20-10|, |10-20| -> 10
    }

    @Test fun pingAllLost() {
        val s = PingStats.from(listOf(null, null))
        assertEquals(100f, s.lossPercent, 0f)
        assertNull(s.avgMs); assertNull(s.jitterMs)
    }

    @Test fun pingSingleReplyHasNoJitter() {
        assertNull(PingStats.from(listOf(12f, null)).jitterMs)
    }

    @Test fun thermalStatusMapping() {
        assertEquals(ThermalLevel.None, ThermalLevel.fromStatus(0))
        assertEquals(ThermalLevel.Severe, ThermalLevel.fromStatus(3))
        assertNull(ThermalLevel.fromStatus(99))
        assertNull(ThermalLevel.fromStatus(-1))
    }

    @Test fun batteryAdvisor() {
        assertEquals(BatteryTempState.Normal, Advisor.batteryTemp(35f))
        assertEquals(BatteryTempState.Warm, Advisor.batteryTemp(40f))
        assertEquals(BatteryTempState.Hot, Advisor.batteryTemp(45f))
    }

    @Test fun highMemoryDetection() {
        assertTrue(Advisor.highMemory(RamInfo(1000, 50, false)))
        assertTrue(Advisor.highMemory(RamInfo(1000, 900, true)))
        assertEquals(false, Advisor.highMemory(RamInfo(1000, 500, false)))
    }

    @Test fun dnsAverageIgnoresFailures() {
        val r = DnsResult(listOf(DnsLookup("a", 10f), DnsLookup("b", null), DnsLookup("c", 30f)))
        assertEquals(20f, r.avgMs!!, 0.001f)
    }
}
