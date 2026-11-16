package app.framex.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

enum class MetricType { RamUsedPercent, CpuLoadPercent, BatteryTempC, PingMs }

@Entity(tableName = "metric_samples", indices = [Index("type", "timestamp")])
data class MetricSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val timestamp: Long,
    val value: Float,
)

@Dao
interface MetricSampleDao {
    @Insert
    suspend fun insertAll(samples: List<MetricSampleEntity>)

    @Query("SELECT value FROM metric_samples WHERE type = :type ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observeRecentValues(type: String, limit: Int): Flow<List<Float>>

    @Query(
        "DELETE FROM metric_samples WHERE type = :type AND id NOT IN " +
            "(SELECT id FROM metric_samples WHERE type = :type ORDER BY timestamp DESC, id DESC LIMIT :keep)"
    )
    suspend fun prune(type: String, keep: Int)

    @Query("DELETE FROM metric_samples")
    suspend fun clear()
}
