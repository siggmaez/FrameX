package app.framex.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(entities = [MetricSampleEntity::class, OptimizationLogEntity::class, GameProfileEntity::class, GameSessionEntity::class, BenchmarkResultEntity::class], version = 5, exportSchema = false)
abstract class FrameXDatabase : RoomDatabase() {
    abstract fun metricSampleDao(): MetricSampleDao
    abstract fun optimizationLogDao(): OptimizationLogDao
    abstract fun gameDao(): GameDao
    abstract fun benchmarkDao(): BenchmarkDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): FrameXDatabase =
        Room.databaseBuilder(context, FrameXDatabase::class.java, "framex.db")
            .fallbackToDestructiveMigration() // development only: history is disposable
            .build()

    @Provides
    fun metricSampleDao(db: FrameXDatabase): MetricSampleDao = db.metricSampleDao()

    @Provides
    fun optimizationLogDao(db: FrameXDatabase): OptimizationLogDao = db.optimizationLogDao()

    @Provides
    fun gameDao(db: FrameXDatabase): GameDao = db.gameDao()

    @Provides
    fun benchmarkDao(db: FrameXDatabase): BenchmarkDao = db.benchmarkDao()
}
