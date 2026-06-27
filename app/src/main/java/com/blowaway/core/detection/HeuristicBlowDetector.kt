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
    private var candidate: Segment? = null
    private var lastTriggerAt = Long.MIN_VALUE / 4
    private var smoothedConfidence = 0f

    override fun analyze(samples: ShortArray, sampleRate: Int, nowMillis: Long): DetectionResult {
        val config = configProvider()
        val features = extractFeatures(samples, sampleRate)
        val thresholds = thresholds(config)
        val rawSpeechConfidence = speechConfidence(features)
        val speechConfidence = if (features.rms < thresholds.startGate) 0f else rawSpeechConfidence
        val currentCandidate = candidate
        val likelyEvent = features.rms > thresholds.noiseGate || currentCandidate != null
        updateNoiseFloor(features.rms, likelyEvent)

        val airflowFrame = isAirflowFrame(features, thresholds, config)
        val strongAirflow = isStrongAirflow(features, thresholds, config)
        val speechFrame = isSpeechFrame(features, speechConfidence) && !strongAirflow
        val candidateFrame = features.rms >= thresholds.startGate &&
            features.peak >= thresholds.peakGate &&
            (airflowFrame || strongAirflow) &&
            !speechFrame
        val inCooldown = nowMillis - lastTriggerAt < config.cooldownMillis

        if (inCooldown) {
            candidate = null
            smoothedConfidence *= 0.82f
            return result(false, features, speechConfidence, "cooldown")
        }

        if (currentCandidate == null && !candidateFrame) {
            smoothedConfidence *= 0.82f
            return result(
                triggered = false,
                features = features,
                speechConfidence = speechConfidence,
                reason = when {
                    features.rms < thresholds.noiseGate -> "ambient"
                    speechFrame -> "speech rejected"
                    features.peak < thresholds.peakGate -> "noise rejected"
                    !airflowFrame -> "not airflow"
                    else -> "below threshold"
                }
            )
        }

        val segment = currentCandidate ?: Segment(startedAt = nowMillis).also { candidate = it }
        if (features.rms >= thresholds.continueGate || candidateFrame) {
            segment.add(features, speechConfidence, airflowFrame || strongAirflow, nowMillis)
        }

        val quietGap = nowMillis - segment.lastActiveAt
        if (quietGap > 140) {
            val decision = decide(segment, thresholds, config)
            candidate = null
            smoothedConfidence *= 0.76f
            return result(false, features, speechConfidence, decision.reason)
        }

        val decision = decide(segment, thresholds, config)
        smoothedConfidence = smoothedConfidence * 0.62f + decision.confidence * 0.38f
        if (decision.triggered) {
            lastTriggerAt = nowMillis
            candidate = null
            return result(true, features, speechConfidence, "blow confirmed")
        }

        if (segment.durationMillis > config.maximumDurationMillis) {
            candidate = null
            smoothedConfidence *= 0.55f
            return result(false, features, speechConfidence, "too long")
        }

        return result(false, features, speechConfidence, decision.reason)
    }

    private fun thresholds(config: DetectionConfig): Thresholds {
        val sensitivityOffset = (config.sensitivity - 0.62f).coerceIn(-0.35f, 0.35f)
        val noiseGate = max(noiseFloor * (1.75f - sensitivityOffset), 0.010f)
        val startGate = max(noiseFloor * (3.15f - sensitivityOffset), config.calibratedRms * (0.34f - sensitivityOffset * 0.14f))
        val continueGate = max(noiseFloor * (2.05f - sensitivityOffset * 0.4f), config.calibratedRms * 0.26f)
        val peakGate = max(0.035f, config.calibratedPeak * 0.14f)
        return Thresholds(noiseGate, startGate, continueGate, peakGate)
    }

    private fun isAirflowFrame(features: BlowFeatures, thresholds: Thresholds, config: DetectionConfig): Boolean {
        val broadband = features.zeroCrossingRate in 0.095f..0.46f
        val pressureBurst = features.zeroCrossingRate >= 0.004f &&
            features.peak > max(thresholds.peakGate * 2.1f, 0.18f) &&
            features.spectralFlatness > 0.42f
        val airy = features.spectralFlatness > 0.16f ||
            features.spectralCentroid > max(1_400f, config.calibratedCentroid * 0.42f)
        return features.rms >= thresholds.continueGate && ((broadband && airy) || pressureBurst)
    }

    private fun isStrongAirflow(features: BlowFeatures, thresholds: Thresholds, config: DetectionConfig): Boolean {
        return features.rms > max(thresholds.startGate * 1.8f, config.calibratedRms * 0.72f) &&
            features.peak > max(thresholds.peakGate * 1.8f, 0.16f) &&
            features.zeroCrossingRate >= 0.004f &&
            features.spectralFlatness > 0.42f
    }

    private fun isSpeechFrame(features: BlowFeatures, speechConfidence: Float): Boolean {
        return speechConfidence > 0.76f && features.zeroCrossingRate < 0.13f && features.spectralFlatness < 0.34f
    }

    private fun decide(segment: Segment, thresholds: Thresholds, config: DetectionConfig): SegmentDecision {
        if (segment.frames == 0) return SegmentDecision(false, 0f, "candidate")
        val duration = segment.durationMillis
        val avgRms = segment.rmsSum / segment.frames
        val avgZcr = segment.zcrSum / segment.frames
        val avgFlatness = segment.flatnessSum / segment.frames
        val avgCentroid = segment.centroidSum / segment.frames
        val speechRatio = segment.speechFrames / segment.frames.toFloat()
        val airflowRatio = segment.airflowFrames / segment.frames.toFloat()
        val clippingRatio = segment.clippedFrames / segment.frames.toFloat()

        val durationScore = when {
            duration < config.minimumDurationMillis -> (duration / config.minimumDurationMillis.toFloat()).coerceIn(0f, 1f) * 0.45f
            duration <= config.maximumDurationMillis -> 1f
            else -> 0f
        }
        val rmsScore = ((avgRms - thresholds.startGate * 0.72f) / max(config.calibratedRms * 0.75f, 0.025f)).coerceIn(0f, 1f)
        val peakScore = (segment.peak / max(config.calibratedPeak, 0.10f)).coerceIn(0f, 1f)
        val zcrScore = (1f - abs(avgZcr - 0.20f) / 0.22f).coerceIn(0f, 1f)
        val flatnessScore = ((avgFlatness - 0.10f) / 0.45f).coerceIn(0f, 1f)
        val centroidScore = (1f - abs(avgCentroid - config.calibratedCentroid) / 5_500f).coerceIn(0f, 1f)
        val shapeScore = (airflowRatio * 0.34f + zcrScore * 0.24f + flatnessScore * 0.18f + centroidScore * 0.10f + peakScore * 0.14f)
        val speechPenalty = (speechRatio * 0.55f).coerceIn(0f, 0.55f)
        val clippingPenalty = if (clippingRatio > 0.65f && avgRms < config.calibratedRms * 1.15f) 0.20f else 0f
        val confidence = (durationScore * 0.24f + rmsScore * 0.34f + shapeScore * 0.42f - speechPenalty - clippingPenalty)
            .coerceIn(0f, 1f)

        val triggered = duration in config.minimumDurationMillis..config.maximumDurationMillis &&
            avgRms >= thresholds.startGate * 0.78f &&
            segment.peak >= thresholds.peakGate &&
            airflowRatio >= 0.38f &&
            (avgZcr >= 0.006f || avgFlatness > 0.54f) &&
            speechRatio < 0.55f &&
            confidence >= 0.58f

        val reason = when {
            triggered -> "blow confirmed"
            duration < config.minimumDurationMillis -> "candidate: too short"
            speechRatio >= 0.55f -> "speech rejected"
            airflowRatio < 0.38f -> "candidate: weak airflow"
            avgRms < thresholds.startGate * 0.78f -> "candidate: low RMS"
            confidence < 0.58f -> "candidate: low confidence"
            else -> "candidate"
        }
        return SegmentDecision(triggered, confidence, reason)
    }

    private fun result(triggered: Boolean, features: BlowFeatures, speechConfidence: Float, reason: String): DetectionResult {
        return DetectionResult(
            triggered = triggered,
            confidence = smoothedConfidence.coerceIn(0f, 1f),
            speechConfidence = speechConfidence,
            noiseFloor = noiseFloor,
            features = features,
            reason = reason
        )
    }

    private fun updateNoiseFloor(rms: Float, likelyEvent: Boolean) {
        if (!likelyEvent && rms < noiseFloor * 1.45f) {
            noiseFloor = noiseFloor * 0.94f + rms * 0.06f
        } else {
            noiseFloor = noiseFloor * 0.999f + min(rms, noiseFloor * 1.08f) * 0.001f
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

    private fun speechConfidence(features: BlowFeatures): Float {
        val voicedZcr = (1f - abs(features.zeroCrossingRate - 0.07f) / 0.08f).coerceIn(0f, 1f)
        val voicedCentroid = (1f - abs(features.spectralCentroid - 950f) / 1_500f).coerceIn(0f, 1f)
        val lowFlatness = (1f - features.spectralFlatness).coerceIn(0f, 1f)
        return (voicedZcr * 0.42f + voicedCentroid * 0.34f + lowFlatness * 0.24f).coerceIn(0f, 1f)
    }

    private data class Thresholds(
        val noiseGate: Float,
        val startGate: Float,
        val continueGate: Float,
        val peakGate: Float
    )

    private data class SegmentDecision(
        val triggered: Boolean,
        val confidence: Float,
        val reason: String
    )

    private data class Segment(
        val startedAt: Long,
        var lastActiveAt: Long = startedAt,
        var frames: Int = 0,
        var rmsSum: Float = 0f,
        var zcrSum: Float = 0f,
        var flatnessSum: Float = 0f,
        var centroidSum: Float = 0f,
        var speechFrames: Int = 0,
        var airflowFrames: Int = 0,
        var clippedFrames: Int = 0,
        var peak: Float = 0f
    ) {
        val durationMillis: Long get() = lastActiveAt - startedAt

        fun add(features: BlowFeatures, speechConfidence: Float, airflow: Boolean, nowMillis: Long) {
            lastActiveAt = nowMillis
            frames += 1
            rmsSum += features.rms
            zcrSum += features.zeroCrossingRate
            flatnessSum += features.spectralFlatness
            centroidSum += features.spectralCentroid
            peak = max(peak, features.peak)
            if (speechConfidence > 0.76f) speechFrames += 1
            if (airflow) airflowFrames += 1
            if (features.clipping) clippedFrames += 1
        }
    }
}