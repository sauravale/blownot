package com.blowaway.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [NotificationEntity::class],
    version = 1,
    exportSchema = false
)
abstract class BlowAwayDatabase : RoomDatabase() {
    abstract fun notificationDao(): NotificationDao
}
