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

    @Test fun liveSamplesBuildFrameTimes() {
        val c = FrameCollector()
        (0..60).forEach { c.addSample(1_000_000_000L + it * 16_666_667L) }
        assertEquals(60, c.frameTimesMs.size)
        assertEquals(60f, c.currentFps()!!, 1f)
        c.addSample(1_000_000_000L + 60 * 16_666_667L + 10_000_000_000L) // 10 s pause is not a frame
        assertEquals(60, c.frameTimesMs.size)
    }
}

class TimeStatsTest {
    private val dump = """
        AverageFPS summary
        layerName = SurfaceView[com.supercell.brawlstars/com.supercell.titan.GameApp]@0(BLAST)#123
        packageName = com.supercell.brawlstars
        totalFrames = 120
        droppedFrames = 0
        averageFPS = 59.842
        presentToPresent histogram is as below:
        0ms=0 1ms=0 15ms=2 16ms=70 17ms=40 33ms=8
        layerName = com.other.app/com.other.Main#0
        totalFrames = 500
        averageFPS = 61.0
        presentToPresent histogram is as below:
        16ms=500
    """.trimIndent()

    @Test fun picksTheGamesLayerAndReadsFpsAndFrameTimes() {
        val s = TimeStatsParser.parse(dump, "com.supercell.brawlstars")!!
        assertEquals(120, s.totalFrames); assertEquals(59.842f, s.averageFps!!, 0.001f)
        assertEquals(120, s.frameTimesMs.size)
        assertEquals(8, s.frameTimesMs.count { it == 33f })
        assertTrue(s.layer.startsWith("SurfaceView"))
    }

    @Test fun supportsTheNameOnItsOwnLineForm() {
        val t = "SurfaceView[com.g.a/com.g.A]@0(BLAST)#9\ntotalFrames = 30\nframeRate = 30.00\naverageFPS = 30.051\n"
        val s = TimeStatsParser.parse(t, "com.g.a")!!
        assertEquals(30, s.totalFrames); assertEquals(30.051f, s.averageFps!!, 0.001f); assertTrue(s.frameTimesMs.isEmpty())
    }

    @Test fun unknownPackageOrEmptyDumpGivesNothing() {
        assertNull(TimeStatsParser.parse(dump, "com.none"))
        assertNull(TimeStatsParser.parse("", "com.g.a"))
    }

    @Test fun collectorTakesExternalFrameTimes() {
        val c = FrameCollector()
        c.addFrameTimes(listOf(16f, 17f, 0f, 9000f))
        assertEquals(2, c.frameTimesMs.size)
    }
}
