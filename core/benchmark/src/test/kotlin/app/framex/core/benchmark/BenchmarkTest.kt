package app.framex.core.benchmark

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BenchmarkTest {
    @Test fun workloadsAreDeterministic() {
        assertEquals(Benchmarks.integerWork(100_000), Benchmarks.integerWork(100_000))
        assertEquals(Benchmarks.floatWork(10_000), Benchmarks.floatWork(10_000), 0.0)
        assertEquals(78498, Benchmarks.sieve(1_000_000)) // known prime count below 1e6
    }

    @Test fun medianAndSpread() {
        assertEquals(2.0, Benchmarks.median(listOf(3.0, 1.0, 2.0)), 0.0)
        assertEquals(100.0, Benchmarks.spreadPercent(listOf(1.0, 2.0, 3.0)), 0.001)
    }

    @Test fun compareDelta() {
        assertEquals(25.0, BenchCompare.deltaPercent(100.0, 125.0)!!, 0.001)
        assertEquals(-50.0, BenchCompare.deltaPercent(100.0, 50.0)!!, 0.001)
        assertNull(BenchCompare.deltaPercent(0.0, 5.0))
    }

    @Test fun codecRoundTrip() {
        val m = linkedMapOf("a" to "1", "b" to "two words")
        assertEquals(m, BenchCodec.decode(BenchCodec.encode(m)))
    }

    @Test fun cpuBenchmarkProducesScores() = runTest {
        val r = Benchmarks.cpu(scale = 200, threads = 2)
        assertTrue(r.score > 0)
        assertNotNull(r.details["multi"])
        assertEquals("2", r.details["threads"])
    }

    @Test fun ramBenchmarkProducesBandwidth() = runTest {
        val r = Benchmarks.ram(megabytes = 4, passes = 2)
        assertTrue(r.score > 0)
        assertNotNull(r.details["read"])
    }

    @Test fun storageBenchmarkCleansUp() = runTest {
        val dir = File(System.getProperty("java.io.tmpdir"), "framex_test_${System.nanoTime()}").also { it.mkdirs() }
        val r = Benchmarks.storage(dir, megabytes = 2)
        assertTrue(r.score > 0)
        assertFalse(File(dir, "framex_bench.tmp").exists())
        dir.deleteRecursively()
    }
}
