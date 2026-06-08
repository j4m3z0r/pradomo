package com.pradomo.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * A persisted track sample, keyed by [mowerId] (the mower's BLE address — demo uses
 * "DEMO"). Keying per mower keeps each yard's map separate and ensures demo data can
 * never mix with a real mower's map.
 */
@Entity(tableName = "map_samples")
data class MapSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mowerId: String,
    val tMillis: Long,
    val x: Float,
    val y: Float,
    val heading: Float,
    val bladeOn: Boolean,
    val cmdLinear: Float,
    val cmdAngular: Float,
)

@Dao
interface MapDao {
    @Query("SELECT * FROM map_samples WHERE mowerId = :mowerId ORDER BY id ASC")
    suspend fun track(mowerId: String): List<MapSampleEntity>

    @Insert
    suspend fun insert(sample: MapSampleEntity)

    @Query("DELETE FROM map_samples WHERE mowerId = :mowerId")
    suspend fun clear(mowerId: String)
}

@Database(entities = [MapSampleEntity::class], version = 1, exportSchema = false)
abstract class MapDatabase : RoomDatabase() {
    abstract fun mapDao(): MapDao

    companion object {
        @Volatile private var instance: MapDatabase? = null

        fun get(context: Context): MapDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, MapDatabase::class.java, "pradomo-map.db",
            ).build().also { instance = it }
        }
    }
}
