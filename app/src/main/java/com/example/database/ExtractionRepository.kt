package com.example.database

import kotlinx.coroutines.flow.Flow

class ExtractionRepository(private val dao: ExtractionRecordDao) {
    val allRecords: Flow<List<ExtractionRecord>> = dao.getAllRecords()

    suspend fun insert(record: ExtractionRecord) {
        dao.insertRecord(record)
    }

    suspend fun deleteById(id: Int) {
        dao.deleteRecordById(id)
    }

    suspend fun deleteAll() {
        dao.deleteAllRecords()
    }
}
