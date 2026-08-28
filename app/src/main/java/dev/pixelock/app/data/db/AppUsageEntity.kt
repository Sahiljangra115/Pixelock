package dev.pixelock.app.data.db

import androidx.room.Entity

@Entity(tableName = "app_usage", primaryKeys = ["date", "packageName"])
data class AppUsageEntity(
    val date: String,
    val packageName: String,
    val totalTime: Long = 0L
)
