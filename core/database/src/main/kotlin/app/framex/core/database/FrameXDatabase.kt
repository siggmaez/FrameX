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

@Database(entities = [MetricSampleEntity::class], version = 1, exportSchema = false)
abstract class FrameXDatabase : RoomDatabase() {
    abstract fun metricSampleDao(): MetricSampleDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): FrameXDatabase =
        Room.databaseBuilder(context, FrameXDatabase::class.java, "framex.db").build()

    @Provides
    fun metricSampleDao(db: FrameXDatabase): MetricSampleDao = db.metricSampleDao()
}
