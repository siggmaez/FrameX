package app.framex.core.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Stores monitoring history. Keeps only the newest [KEEP_PER_TYPE] samples per metric. */
@Singleton
class MetricHistoryRepository @Inject constructor(private val dao: MetricSampleDao) {
    private var sinceLastPrune = 0

    suspend fun record(type: MetricType, value: Float, timestamp: Long = System.currentTimeMillis()) {
        dao.insertAll(listOf(MetricSampleEntity(type = type.name, timestamp = timestamp, value = value)))
        if (++sinceLastPrune >= PRUNE_EVERY) {
            sinceLastPrune = 0
            MetricType.entries.forEach { dao.prune(it.name, KEEP_PER_TYPE) }
        }
    }

    /** Newest [limit] values in chronological order (oldest first). */
    fun history(type: MetricType, limit: Int): Flow<List<Float>> =
        dao.observeRecentValues(type.name, limit).map { it.asReversed() }

    suspend fun clear() = dao.clear()

    companion object {
        const val KEEP_PER_TYPE = 2000
        const val PRUNE_EVERY = 100
    }
}
