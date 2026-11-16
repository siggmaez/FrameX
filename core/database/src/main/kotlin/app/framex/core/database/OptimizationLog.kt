package app.framex.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Entity(tableName = "optimization_log")
data class OptimizationLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val profile: String,
    val action: String,
    /** Applied / Restored / Failed / Skipped */
    val status: String,
    val message: String,
)

@Dao
interface OptimizationLogDao {
    @Insert suspend fun insert(e: OptimizationLogEntity)
    @Query("SELECT * FROM optimization_log ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<OptimizationLogEntity>>
    @Query("DELETE FROM optimization_log") suspend fun clear()
}

@Singleton
class OptimizationLogRepository @Inject constructor(private val dao: OptimizationLogDao) {
    suspend fun log(profile: String, action: String, status: String, message: String, ts: Long = System.currentTimeMillis()) =
        dao.insert(OptimizationLogEntity(timestamp = ts, profile = profile, action = action, status = status, message = message))
    fun recent(limit: Int = 30): Flow<List<OptimizationLogEntity>> = dao.observeRecent(limit)
    suspend fun clear() = dao.clear()
}
