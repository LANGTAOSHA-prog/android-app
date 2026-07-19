package com.example.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ExtractionRecordDao {
    @Query("SELECT * FROM extraction_records ORDER BY timestamp DESC")
    fun getAllRecords(): Flow<List<ExtractionRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: ExtractionRecord)

    @Query("DELETE FROM extraction_records WHERE id = :id")
    suspend fun deleteRecordById(id: Int)

    @Query("DELETE FROM extraction_records")
    suspend fun deleteAllRecords()
}
