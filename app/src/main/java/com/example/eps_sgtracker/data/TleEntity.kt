package com.example.eps_sgtracker.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "satellite_tles")
data class TleEntity(
    @PrimaryKey val noradId: Int,
    val rawTleText: String,
    val lastUpdatedMillis: Long
)

@Dao
interface TleDao {
    @Query("SELECT * FROM satellite_tles WHERE noradId = :noradId LIMIT 1")
    suspend fun getTleForSatellite(noradId: Int): TleEntity?

    @Query("SELECT * FROM satellite_tles")
    fun getAllStoredTlesFlow(): Flow<List<TleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateTle(tle: TleEntity)

    @Query("DELETE FROM satellite_tles WHERE noradId = :noradId")
    suspend fun deleteTle(noradId: Int)
}