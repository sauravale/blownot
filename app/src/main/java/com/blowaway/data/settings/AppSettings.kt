package com.blowaway.data.settings

data class AppSettings(
    val enabled: Boolean = true,
    val startOnBoot: Boolean = true,
    val foregroundServiceEnabled: Boolean = true,
    val sensitivity: Float = 0.62f,
    val cooldownMillis: Long = 2_000,
    val listeningWindowMillis: Long = 3_000,
    val gestureDurationMillis: Long = 200,
    val ignoreAlarms: Boolean = true,
    val ignoreMedia: Boolean = true,
    val averageRms: Float = 0.11f,
    val peakAmplitude: Float = 0.36f,
    val spectralCentroid: Float = 3_200f
)
