package com.blowaway.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notifications")
data class NotificationEntity(
    @PrimaryKey val key: String,
    val timestampMillis: Long,
    val packageName: String,
    val category: String?,
    val importance: Int,
    val activeUntilMillis: Long
)
