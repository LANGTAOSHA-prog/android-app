package com.example.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "extraction_records")
data class ExtractionRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val fileName: String,
    val actionType: String, // TEXT, IMAGES, SPLIT, UNLOCKED
    val timestamp: Long = System.currentTimeMillis(),
    val resultSummary: String,
    val filePath: String? = null // local file/folder path where results were stored
)
