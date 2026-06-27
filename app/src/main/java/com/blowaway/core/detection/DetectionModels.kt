package com.blowaway.core.detection

data class DetectionConfig(
    val sensitivity: Float = 0.62f,
    val cooldownMillis: Long = 2_000,
    val minimumDurationMillis: Long = 150,
    val maximumDurationMillis: Long = 700,
    val calibratedRms: Float = 0.11f,
    val calibratedPeak: Float = 0.36f,
    val calibratedCentroid: Float = 3_200f
)

data class BlowFeatures(
    val rms: Float,
    val peak: Float,
    val zeroCrossingRate: Float,
    val spectralCentroid: Float,
    val spectralFlatness: Float,
    val frameEnergy: Float,
    val clipping: Boolean
)

data class DetectionResult(
    val triggered: Boolean,
    val confidence: Float,
    val speechConfidence: Float,
    val features: BlowFeatures,
    val reason: String
)

interface BlowDetector {
    fun analyze(samples: ShortArray, sampleRate: Int, nowMillis: Long): DetectionResult
}
