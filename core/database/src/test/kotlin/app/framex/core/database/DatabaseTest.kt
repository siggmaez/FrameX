package app.framex.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseTest {
    private lateinit var db: FrameXDatabase

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FrameXDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun close() = db.close()

    @Test fun metricHistoryIsChronologicalAndLimited() = runBlocking {
        val repo = MetricHistoryRepository(db.metricSampleDao())
        (1..5).forEach { repo.record(MetricType.CpuLoadPercent, it.toFloat(), timestamp = it * 1000L) }
        repo.record(MetricType.RamUsedPercent, 99f, timestamp = 10)
        assertEquals(listOf(3f, 4f, 5f), repo.history(MetricType.CpuLoadPercent, 3).first())
        assertEquals(listOf(99f), repo.history(MetricType.RamUsedPercent, 10).first())
    }

    @Test fun pruneKeepsNewest() = runBlocking {
        val dao = db.metricSampleDao()
        dao.insertAll((1..10).map { MetricSampleEntity(type = "T", timestamp = it.toLong(), value = it.toFloat()) })
        dao.prune("T", 3)
        assertEquals(listOf(10f, 9f, 8f), dao.observeRecentValues("T", 50).first())
    }

    @Test fun clearRemovesEverything() = runBlocking {
        val repo = MetricHistoryRepository(db.metricSampleDao())
        repo.record(MetricType.PingMs, 12f)
        repo.clear()
        assertTrue(repo.history(MetricType.PingMs, 10).first().isEmpty())
    }

    @Test fun optimizationLogNewestFirst() = runBlocking {
        val repo = OptimizationLogRepository(db.optimizationLogDao())
        repo.log("Gaming", "DoNotDisturb", "Applied", "ok", ts = 1)
        repo.log("Gaming", "StopBackgroundApps", "Failed", "boom", ts = 2)
        val l = repo.recent().first()
        assertEquals(listOf("StopBackgroundApps", "DoNotDisturb"), l.map { it.action })
        assertEquals("Failed", l.first().status)
    }

    @Test fun gameProfileUpsertReplacesAndDeletes() = runBlocking {
        val repo = GamesRepository(db.gameDao())
        repo.save(GameProfileEntity("com.g.a", "Game", "Gaming", true))
        repo.save(GameProfileEntity("com.g.a", "Game", "Performance", false))
        val p = repo.profiles().first().single()
        assertEquals("Performance", p.profile); assertEquals(false, p.autoApply)
        repo.remove("com.g.a")
        assertTrue(repo.profiles().first().isEmpty())
    }

    @Test fun sessionLifecycleAndStaleClosing() = runBlocking {
        val repo = GamesRepository(db.gameDao())
        val id = repo.startSession(GameSessionEntity(packageName = "com.g.a", label = "Game", startMs = 100, startTempC = 33f))
        repo.closeStaleSessions(now = 500)
        val s = repo.sessions().first().single()
        assertEquals(id, s.id); assertEquals(500L, s.endMs)
        assertNull(s.avgFps)
        repo.updateSession(s.copy(avgFps = 58f, minFps = 40f, lowFps = 35f))
        assertEquals(58f, repo.sessions(1).first().single().avgFps)
    }

    @Test fun benchmarkResultsStoredWithDeviceInfo() = runBlocking {
        val repo = BenchmarkRepository(db.benchmarkDao())
        repo.save(BenchmarkResultEntity(type = "Cpu", score = 1234.5, unit = "points", details = "single=1.0", timestamp = 1, deviceInfo = "Redmi 14C", profile = null))
        repo.save(BenchmarkResultEntity(type = "Cpu", score = 1300.0, unit = "points", details = "single=1.1", timestamp = 2, deviceInfo = "Redmi 14C", profile = "Gaming"))
        val r = repo.results().first()
        assertEquals(listOf(1300.0, 1234.5), r.map { it.score })
        assertEquals("Redmi 14C", r.first().deviceInfo); assertEquals("Gaming", r.first().profile); assertNull(r.last().profile)
        repo.delete(r.first().id)
        assertEquals(1, repo.results().first().size)
    }
}
