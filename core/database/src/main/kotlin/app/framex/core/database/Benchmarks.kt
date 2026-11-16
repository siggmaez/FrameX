package app.framex.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Entity(tableName = "benchmark_results")
data class BenchmarkResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Cpu / Ram / Storage / Graphics */
    val type: String,
    val score: Double,
    val unit: String,
    /** key=value lines with the sub-results and test conditions */
    val details: String,
    val timestamp: Long,
    /** Device attached to every result, so results from different phones are never mixed up. */
    val deviceInfo: String,
    /** Optimization profile that was applied when the test ran, null if none. */
    val profile: String?,
)

@Dao
interface BenchmarkDao {
    @Insert suspend fun insert(r: BenchmarkResultEntity): Long
    @Query("SELECT * FROM benchmark_results ORDER BY timestamp DESC LIMIT :limit") fun observe(limit: Int): Flow<List<BenchmarkResultEntity>>
    @Query("DELETE FROM benchmark_results WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM benchmark_results") suspend fun clear()
}

@Singleton
class BenchmarkRepository @Inject constructor(private val dao: BenchmarkDao) {
    fun results(limit: Int = 100): Flow<List<BenchmarkResultEntity>> = dao.observe(limit)
    suspend fun save(r: BenchmarkResultEntity) = dao.insert(r)
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun clear() = dao.clear()
}
