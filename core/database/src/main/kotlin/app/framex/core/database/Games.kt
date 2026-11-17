package app.framex.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Entity(tableName = "game_profiles")
data class GameProfileEntity(
    @PrimaryKey val packageName: String,
    val label: String,
    /** OptimizationProfile name */
    val profile: String,
    val autoApply: Boolean,
    /** Comma-separated packages the user approved to stop when playing; empty = ask. */
    val stopPackages: String = "",
    /** Off / Safe / Strong / Max: how far this game's boost goes. */
    val boostLevel: String = "Strong",
    /** Also reduce lag sources on the phone while this game runs (Wi-Fi low-latency modes, Data Saver). */
    val pingBoost: Boolean = false,
)

@Entity(tableName = "game_sessions")
data class GameSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val label: String,
    val startMs: Long,
    val endMs: Long? = null,
    /** Profile that was actually applied for this session, null if nothing was applied. */
    val appliedProfile: String? = null,
    val startTempC: Float? = null,
    val endTempC: Float? = null,
    val maxTempC: Float? = null,
    /** Measured through SurfaceFlinger when Shizuku was connected; null means FPS was not measurable. */
    val avgFps: Float? = null,
    val minFps: Float? = null,
    val lowFps: Float? = null,
    /** "SurfaceFlinger" = measured; "ScreenCapture" = estimated from screen-capture frame updates. */
    val fpsSource: String? = null,
    /** Boost level used for this session (null on sessions recorded before boosts existed). */
    val boostLevel: String? = null,
)

@Dao
interface GameDao {
    @Query("SELECT * FROM game_profiles ORDER BY label COLLATE NOCASE") fun observeProfiles(): Flow<List<GameProfileEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertProfile(p: GameProfileEntity)
    @Query("DELETE FROM game_profiles WHERE packageName = :pkg") suspend fun deleteProfile(pkg: String)

    @Insert suspend fun insertSession(s: GameSessionEntity): Long
    @Update suspend fun updateSession(s: GameSessionEntity)
    @Query("SELECT * FROM game_sessions ORDER BY startMs DESC LIMIT :limit") fun observeSessions(limit: Int): Flow<List<GameSessionEntity>>
    @Query("SELECT * FROM game_sessions WHERE endMs IS NULL") suspend fun openSessions(): List<GameSessionEntity>
    @Query("DELETE FROM game_sessions") suspend fun clearSessions()
}

@Singleton
class GamesRepository @Inject constructor(private val dao: GameDao) {
    fun profiles(): Flow<List<GameProfileEntity>> = dao.observeProfiles()
    suspend fun save(p: GameProfileEntity) = dao.upsertProfile(p)
    suspend fun remove(pkg: String) = dao.deleteProfile(pkg)
    fun sessions(limit: Int = 30): Flow<List<GameSessionEntity>> = dao.observeSessions(limit)
    suspend fun startSession(s: GameSessionEntity): Long = dao.insertSession(s)
    suspend fun updateSession(s: GameSessionEntity) = dao.updateSession(s)
    /** Sessions left open by a crash or killed process get closed at their last known time. */
    suspend fun closeStaleSessions(now: Long) = dao.openSessions().forEach { dao.updateSession(it.copy(endMs = now)) }
    suspend fun clearSessions() = dao.clearSessions()
}
