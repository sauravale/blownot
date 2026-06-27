package com.blowaway.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
            fallbackMode = preferences[Keys.fallbackMode]?.let(FallbackMode::valueOf) ?: FallbackMode.WindowMetrics,
            screenMustBeOn = preferences[Keys.screenMustBeOn] ?: true,
            deviceUnlockedOnly = preferences[Keys.deviceUnlockedOnly] ?: true,
            ignoreAlarms = preferences[Keys.ignoreAlarms] ?: true,
            ignorePhoneCalls = preferences[Keys.ignorePhoneCalls] ?: true,
            ignoreNavigation = preferences[Keys.ignoreNavigation] ?: true,
            ignoreMedia = preferences[Keys.ignoreMedia] ?: true,
            ignoreDnd = preferences[Keys.ignoreDnd] ?: true,
            ignoreCharging = preferences[Keys.ignoreCharging] ?: true,
            averageRms = preferences[Keys.averageRms] ?: 0.11f,
            averageDurationMillis = preferences[Keys.averageDurationMillis] ?: 320,
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
            preferences[Keys.fallbackMode] = settings.fallbackMode.name
            preferences[Keys.screenMustBeOn] = settings.screenMustBeOn
            preferences[Keys.deviceUnlockedOnly] = settings.deviceUnlockedOnly
            preferences[Keys.ignoreAlarms] = settings.ignoreAlarms
            preferences[Keys.ignorePhoneCalls] = settings.ignorePhoneCalls
            preferences[Keys.ignoreNavigation] = settings.ignoreNavigation
            preferences[Keys.ignoreMedia] = settings.ignoreMedia
            preferences[Keys.ignoreDnd] = settings.ignoreDnd
            preferences[Keys.ignoreCharging] = settings.ignoreCharging
            preferences[Keys.averageRms] = settings.averageRms
            preferences[Keys.averageDurationMillis] = settings.averageDurationMillis
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
        val fallbackMode = stringPreferencesKey("fallback_mode")
        val screenMustBeOn = booleanPreferencesKey("screen_must_be_on")
        val deviceUnlockedOnly = booleanPreferencesKey("device_unlocked_only")
        val ignoreAlarms = booleanPreferencesKey("ignore_alarms")
        val ignorePhoneCalls = booleanPreferencesKey("ignore_phone_calls")
        val ignoreNavigation = booleanPreferencesKey("ignore_navigation")
        val ignoreMedia = booleanPreferencesKey("ignore_media")
        val ignoreDnd = booleanPreferencesKey("ignore_dnd")
        val ignoreCharging = booleanPreferencesKey("ignore_charging")
        val averageRms = floatPreferencesKey("average_rms")
        val averageDurationMillis = longPreferencesKey("average_duration_millis")
        val peakAmplitude = floatPreferencesKey("peak_amplitude")
        val spectralCentroid = floatPreferencesKey("spectral_centroid")
    }
}
