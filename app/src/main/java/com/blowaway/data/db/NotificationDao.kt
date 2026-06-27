package com.blowaway.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: NotificationEntity)

    @Query("DELETE FROM notifications WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("SELECT * FROM notifications WHERE activeUntilMillis > :nowMillis ORDER BY timestampMillis DESC LIMIT 1")
    fun activeNotification(nowMillis: Long): Flow<NotificationEntity?>

    @Query("SELECT * FROM notifications ORDER BY timestampMillis DESC LIMIT 25")
    fun recentNotifications(): Flow<List<NotificationEntity>>
}
