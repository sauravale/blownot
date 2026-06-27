package com.blowaway.di

import android.content.Context
import androidx.room.Room
import com.blowaway.core.detection.BlowDetector
import com.blowaway.core.detection.DetectionConfig
import com.blowaway.core.detection.HeuristicBlowDetector
import com.blowaway.data.db.BlowAwayDatabase
import com.blowaway.data.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BlowAwayDatabase {
        return Room.databaseBuilder(context, BlowAwayDatabase::class.java, "blowaway.db").build()
    }

    @Provides
    fun provideNotificationDao(database: BlowAwayDatabase) = database.notificationDao()

    @Provides
    @Singleton
    fun provideDetector(settingsRepository: SettingsRepository): BlowDetector {
        return HeuristicBlowDetector {
            val settings = runBlocking { settingsRepository.settings.first() }
            DetectionConfig(
                sensitivity = settings.sensitivity,
                cooldownMillis = settings.cooldownMillis,
                calibratedRms = settings.averageRms,
                calibratedPeak = settings.peakAmplitude,
                calibratedCentroid = settings.spectralCentroid
            )
        }
    }
}
