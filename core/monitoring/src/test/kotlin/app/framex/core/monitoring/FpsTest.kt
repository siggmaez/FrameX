package app.framex.core.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FpsTest {
    @Test fun steady60Fps() {
        val s = FrameStats.from(List(300) { 16.6667f })!!
        assertEquals(60f, s.avgFps, 0.1f)
        assertEquals(60f, s.currentFps!!, 0.5f)
        assertEquals(60f, s.minFps!!, 1f)
        assertEquals(60f, s.onePercentLowFps!!, 0.5f)
        assertEquals(100f, s.stabilityPercent, 0.01f)
    }

    @Test fun tooFewFramesGivesNothing() {
        assertNull(FrameStats.from(emptyList()))
        assertNull(FrameStats.from(listOf(16f)))
    }

    @Test fun onePercentLowNeedsEnoughSamples() {
        assertNull(FrameStats.from(List(99) { 16f })!!.onePercentLowFps)
        assertNotNull(FrameStats.from(List(100) { 16f })!!.onePercentLowFps)
    }

    @Test fun hitchesLowerLowAndStability() {
        val times = MutableList(200) { 10f }
        times[50] = 100f; times[150] = 100f // 2 of 200 = 1% janky frames
        val s = FrameStats.from(times)!!
        assertEquals(99f, s.stabilityPercent, 0.01f)
        assertEquals(10f, s.onePercentLowFps!!, 0.5f) // slowest 2 frames avg 100ms -> 10 fps
        assertEquals(100f, s.maxFrameMs, 0f)
    }

    @Test fun shortRunHasNoMin() {
        assertNull(FrameStats.from(List(30) { 16f })!!.minFps) // 0.48 s, no full second
    }

    @Test fun parserSkipsPendingAndZero() {
        val txt = "16666666\n100 1000000 110\n200 2000000 210\n0 0 0\n300 9223372036854775807 310\n400 3000000 410"
        assertEquals(listOf(1000000L, 2000000L, 3000000L), SurfaceFlingerLatencyParser.parse(txt))
    }

    @Test fun collectorDedupesOverlappingDumps() {
        val c = FrameCollector()
        c.add(listOf(0L, 16_000_000L, 32_000_000L).map { it + 1 })
        c.add(listOf(16_000_000L, 32_000_000L, 48_000_000L, 64_000_000L).map { it + 1 })
        assertEquals(4, c.frameTimesMs.size) // 5 distinct timestamps -> 4 intervals
        assertTrue(c.frameTimesMs.all { it > 15.9f && it < 16.1f })
    }

    @Test fun collectorIgnoresLongPauses() {
        val c = FrameCollector()
        c.add(listOf(1_000_000L, 17_000_000L, 20_000_000_000L, 20_016_000_000L))
        assertEquals(2, c.frameTimesMs.size)
    }

    @Test fun layerPick() {
        val layers = listOf("Background for SurfaceView[com.g/.A]@0", "com.g/com.g.Main#0", "SurfaceView[com.g/com.g.Main]@0(BLAST)#5", "com.other/.X#1")
        assertEquals("SurfaceView[com.g/com.g.Main]@0(BLAST)#5", SurfaceFlingerLayers.pick(layers, "com.g"))
        assertNull(SurfaceFlingerLayers.pick(layers, "com.none"))
    }

    @Test fun collectorStartsNewBaselineAfterMissedFrames() {
        val c = FrameCollector()
        c.add(listOf(1_000_000L, 17_000_000L))                 // 1 interval
        c.add(listOf(2_000_000_000L, 2_016_000_000L))          // no overlap: gap, only the new pair counts
        assertEquals(2, c.frameTimesMs.size)
        assertTrue(c.frameTimesMs.all { it < 20f })
    }

    @Test fun currentFpsOverLastSecond() {
        val c = FrameCollector()
        c.add((0..120).map { it * 16_666_667L + 1 })
        assertEquals(60f, c.currentFps()!!, 1f)
        assertNull(FrameCollector().currentFps())
    }
}
