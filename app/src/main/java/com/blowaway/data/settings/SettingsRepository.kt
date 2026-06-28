package com.blowaway.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore("settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { preferences ->
        AppSettings(
            enabled = preferences[Keys.enabled] ?: true,
            startOnBoot = preferences[Keys.startOnBoot] ?: true,
            foregroundServiceEnabled = preferences[Keys.foregroundServiceEnabled] ?: true,
            sensitivity = preferences[Keys.sensitivity] ?: 0.62f,
            cooldownMillis = preferences[Keys.cooldownMillis] ?: 2_000,
            listeningWindowMillis = preferences[Keys.listeningWindowMillis] ?: 3_000,
            gestureDurationMillis = preferences[Keys.gestureDurationMillis] ?: 200,
            ignoreAlarms = preferences[Keys.ignoreAlarms] ?: true,
            ignoreMedia = preferences[Keys.ignoreMedia] ?: true,
            averageRms = preferences[Keys.averageRms] ?: 0.11f,
            peakAmplitude = preferences[Keys.peakAmplitude] ?: 0.36f,
            spectralCentroid = preferences[Keys.spectralCentroid] ?: 3_200f
        )
    }

    suspend fun update(settings: AppSettings) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.enabled] = settings.enabled
            preferences[Keys.startOnBoot] = settings.startOnBoot
            preferences[Keys.foregroundServiceEnabled] = settings.foregroundServiceEnabled
            preferences[Keys.sensitivity] = settings.sensitivity
            preferences[Keys.cooldownMillis] = settings.cooldownMillis
            preferences[Keys.listeningWindowMillis] = settings.listeningWindowMillis
            preferences[Keys.gestureDurationMillis] = settings.gestureDurationMillis
            preferences[Keys.ignoreAlarms] = settings.ignoreAlarms
            preferences[Keys.ignoreMedia] = settings.ignoreMedia
            preferences[Keys.averageRms] = settings.averageRms
            preferences[Keys.peakAmplitude] = settings.peakAmplitude
            preferences[Keys.spectralCentroid] = settings.spectralCentroid
        }
    }

    private object Keys {
        val enabled = booleanPreferencesKey("enabled")
        val startOnBoot = booleanPreferencesKey("start_on_boot")
        val foregroundServiceEnabled = booleanPreferencesKey("foreground_service_enabled")
        val sensitivity = floatPreferencesKey("sensitivity")
        val cooldownMillis = longPreferencesKey("cooldown_millis")
        val listeningWindowMillis = longPreferencesKey("listening_window_millis")
        val gestureDurationMillis = longPreferencesKey("gesture_duration_millis")
        val ignoreAlarms = booleanPreferencesKey("ignore_alarms")
        val ignoreMedia = booleanPreferencesKey("ignore_media")
        val averageRms = floatPreferencesKey("average_rms")
        val peakAmplitude = floatPreferencesKey("peak_amplitude")
        val spectralCentroid = floatPreferencesKey("spectral_centroid")
    }
}
