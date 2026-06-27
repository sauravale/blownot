package com.blowaway.core.detection

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class HeuristicBlowDetector(
    private val configProvider: () -> DetectionConfig
) : BlowDetector {
    private var noiseFloor = 0.012f
    private var candidateStartedAt: Long? = null
    private var lastTriggerAt = Long.MIN_VALUE / 4

    override fun analyze(samples: ShortArray, sampleRate: Int, nowMillis: Long): DetectionResult {
        val config = configProvider()
        val features = extractFeatures(samples, sampleRate)
        updateNoiseFloor(features.rms)

        val threshold = max(noiseFloor * (2.0f - config.sensitivity), config.calibratedRms * 0.45f)
        val airflowScore = airflowScore(features, threshold, config)
        val speechConfidence = speechConfidence(features)
        val overThreshold = features.rms > threshold && airflowScore > 0.58f
        val inCooldown = nowMillis - lastTriggerAt < config.cooldownMillis

        if (!overThreshold || features.clipping || speechConfidence > 0.55f || inCooldown) {
            candidateStartedAt = null
            return DetectionResult(
                triggered = false,
                confidence = airflowScore,
                speechConfidence = speechConfidence,
                features = features,
                reason = when {
                    inCooldown -> "cooldown"
                    features.clipping -> "clipping"
                    speechConfidence > 0.55f -> "voice rejected"
                    else -> "below threshold"
                }
            )
        }

        val startedAt = candidateStartedAt ?: nowMillis.also { candidateStartedAt = it }
        val duration = nowMillis - startedAt
        val triggered = duration in config.minimumDurationMillis..config.maximumDurationMillis
        if (triggered) {
            lastTriggerAt = nowMillis
            candidateStartedAt = null
        }

        return DetectionResult(
            triggered = triggered,
            confidence = airflowScore,
            speechConfidence = speechConfidence,
            features = features,
            reason = if (triggered) "blow confirmed" else "candidate"
        )
    }

    private fun updateNoiseFloor(rms: Float) {
        if (rms < noiseFloor * 1.35f) {
            noiseFloor = noiseFloor * 0.96f + rms * 0.04f
        } else {
            noiseFloor = noiseFloor * 0.995f + min(rms, noiseFloor * 1.35f) * 0.005f
        }
        noiseFloor = noiseFloor.coerceIn(0.004f, 0.18f)
    }

    private fun extractFeatures(samples: ShortArray, sampleRate: Int): BlowFeatures {
        if (samples.isEmpty()) {
            return BlowFeatures(0f, 0f, 0f, 0f, 0f, 0f, clipping = false)
        }

        var energy = 0.0
        var peak = 0f
        var crossings = 0
        var previous = samples.first()

        samples.forEach { sample ->
            val normalized = sample / Short.MAX_VALUE.toFloat()
            energy += normalized * normalized
            peak = max(peak, abs(normalized))
            if ((sample >= 0 && previous < 0) || (sample < 0 && previous >= 0)) {
                crossings += 1
            }
            previous = sample
        }

        val rms = sqrt(energy / samples.size).toFloat()
        val zeroCrossingRate = crossings / samples.size.toFloat()
        val centroid = zeroCrossingRate * sampleRate * 0.5f
        val flatness = spectralFlatnessEstimate(samples)
        return BlowFeatures(
            rms = rms,
            peak = peak,
            zeroCrossingRate = zeroCrossingRate,
            spectralCentroid = centroid,
            spectralFlatness = flatness,
            frameEnergy = energy.toFloat(),
            clipping = peak > 0.97f
        )
    }

    private fun spectralFlatnessEstimate(samples: ShortArray): Float {
        var geometric = 0.0
        var arithmetic = 0.0
        samples.forEach { sample ->
            val magnitude = abs(sample / Short.MAX_VALUE.toFloat()).coerceAtLeast(0.000_001f)
            geometric += ln(magnitude)
            arithmetic += magnitude
        }
        val geoMean = exp(geometric / samples.size)
        val arithmeticMean = arithmetic / samples.size
        return (geoMean / arithmeticMean).toFloat().coerceIn(0f, 1f)
    }

    private fun airflowScore(features: BlowFeatures, threshold: Float, config: DetectionConfig): Float {
        val rmsScore = ((features.rms - threshold) / max(config.calibratedRms, 0.02f)).coerceIn(0f, 1f)
        val peakScore = (features.peak / max(config.calibratedPeak, 0.05f)).coerceIn(0f, 1f)
        val zcrScore = (1f - abs(features.zeroCrossingRate - 0.22f) / 0.22f).coerceIn(0f, 1f)
        val centroidScore = (1f - abs(features.spectralCentroid - config.calibratedCentroid) / 3_400f).coerceIn(0f, 1f)
        val flatnessScore = features.spectralFlatness.coerceIn(0f, 1f)
        return (rmsScore * 0.34f + peakScore * 0.16f + zcrScore * 0.2f + centroidScore * 0.16f + flatnessScore * 0.14f)
            .coerceIn(0f, 1f)
    }

    private fun speechConfidence(features: BlowFeatures): Float {
        val voicedZcr = (1f - abs(features.zeroCrossingRate - 0.07f) / 0.08f).coerceIn(0f, 1f)
        val voicedCentroid = (1f - abs(features.spectralCentroid - 950f) / 1_500f).coerceIn(0f, 1f)
        val lowFlatness = (1f - features.spectralFlatness).coerceIn(0f, 1f)
        return (voicedZcr * 0.42f + voicedCentroid * 0.34f + lowFlatness * 0.24f).coerceIn(0f, 1f)
    }
}
