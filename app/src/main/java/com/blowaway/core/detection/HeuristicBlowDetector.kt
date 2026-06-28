package com.blowaway.core.detection

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class HeuristicBlowDetector(
    private val configProvider: () -> DetectionConfig
) : BlowDetector {
    private var noiseFloor = 0.0015f
    private var candidate: Segment? = null
    private var lastTriggerAt = Long.MIN_VALUE / 4
    private var smoothedConfidence = 0f

    override fun analyze(samples: ShortArray, sampleRate: Int, nowMillis: Long): DetectionResult {
        val config = configProvider()
        val features = extractFeatures(samples, sampleRate)
        val gates = gates(config)
        val rawSpeechConfidence = speechConfidence(features)
        val speechConfidence = if (features.rms < gates.eventGate) 0f else rawSpeechConfidence
        val currentCandidate = candidate
        val inCooldown = nowMillis - lastTriggerAt < config.cooldownMillis
        val likelyEvent = features.rms > gates.noiseGate || currentCandidate != null
        updateNoiseFloor(features.rms, likelyEvent)

        if (inCooldown) {
            candidate = null
            smoothedConfidence *= 0.82f
            return result(false, features, speechConfidence, "cooldown")
        }

        val speechFrame = isSpeechFrame(features, speechConfidence)
        val activeFrame = isActiveFrame(features, gates, speechFrame)
        val continuingFrame = currentCandidate != null &&
            features.rms >= gates.templateContinueGate &&
            features.peak >= max(gates.peakGate, 0.006f) &&
            !speechFrame &&
            features.spectralTemplateScore >= 0.50f

        if (currentCandidate == null && !activeFrame) {
            smoothedConfidence *= 0.82f
            return result(
                triggered = false,
                features = features,
                speechConfidence = speechConfidence,
                reason = when {
                    speechFrame -> "speech rejected"
                    features.rms < gates.noiseGate -> "ambient"
                    features.peak < gates.peakGate -> "noise rejected"
                    !looksLikeAirflow(features) -> "not airflow"
                    else -> "below threshold"
                }
            )
        }

        val segment = currentCandidate ?: Segment(startedAt = nowMillis).also { candidate = it }
        if (activeFrame || continuingFrame) {
            segment.add(features, speechConfidence, activeFrame, nowMillis)
        }

        val quietGap = nowMillis - segment.lastActiveAt
        if (quietGap > 120) {
            val decision = decide(segment, config)
            candidate = null
            smoothedConfidence = smoothedConfidence * 0.62f + decision.confidence * 0.38f
            if (decision.triggered) {
                lastTriggerAt = nowMillis
                return result(true, features, speechConfidence, decision.reason)
            }
            smoothedConfidence *= 0.76f
            return result(false, features, speechConfidence, decision.reason)
        }

        val decision = decide(segment, config)
        smoothedConfidence = smoothedConfidence * 0.62f + decision.confidence * 0.38f
        val liveReason = if (decision.triggered) "candidate: template match" else decision.reason

        if (segment.durationMillis > config.maximumDurationMillis) {
            candidate = null
            smoothedConfidence *= 0.55f
            return result(false, features, speechConfidence, "too long")
        }

        return result(false, features, speechConfidence, liveReason)
    }

    private fun gates(config: DetectionConfig): Gates {
        val sensitivityOffset = (config.sensitivity - 0.62f).coerceIn(-0.35f, 0.35f)
        val noiseGate = max(noiseFloor * (1.45f - sensitivityOffset * 0.28f), 0.0008f)
        val eventGate = max(noiseFloor * (1.10f - sensitivityOffset * 0.22f), 0.0008f)
        val continueGate = max(noiseFloor * (0.92f - sensitivityOffset * 0.16f), 0.0005f)
        val peakGate = max(0.0025f, config.calibratedPeak * 0.012f * (1f - sensitivityOffset * 0.18f))
        val templateStartGate = max(noiseFloor * 3.2f, 0.0025f)
        val templateContinueGate = max(noiseFloor * 3.0f, 0.0030f)
        val templateConfirmGate = max(noiseFloor * 6.0f, 0.0060f)
        return Gates(noiseGate, eventGate, continueGate, peakGate, templateStartGate, templateContinueGate, templateConfirmGate)
    }

    private fun isActiveFrame(features: BlowFeatures, gates: Gates, speechFrame: Boolean): Boolean {
        if (speechFrame) return false
        return features.rms >= gates.templateStartGate &&
            features.peak >= max(gates.peakGate, 0.006f) &&
            features.spectralTemplateScore >= 0.58f
    }

    private fun looksLikeAirflow(features: BlowFeatures): Boolean {
        return (features.rms >= 0.0025f && features.spectralTemplateScore >= 0.58f) ||
            (features.zeroCrossingRate >= 0.16f && features.spectralFlatness >= 0.05f)
    }

    private fun isSpeechFrame(features: BlowFeatures, speechConfidence: Float): Boolean {
        return speechConfidence > 0.78f &&
            features.zeroCrossingRate < 0.14f &&
            features.spectralFlatness < 0.16f &&
            features.spectralTemplateScore < 0.55f
    }

    private fun decide(segment: Segment, config: DetectionConfig): SegmentDecision {
        if (segment.frames == 0) return SegmentDecision(false, 0f, "candidate")
        val duration = segment.durationMillis
        val speechRatio = segment.speechFrames / segment.frames.toFloat()
        val activeRatio = segment.activeFrames / segment.frames.toFloat()
        val activeRatioRmsGt0001 = segment.rmsAbove0001Frames / segment.frames.toFloat()
        val activeRatioRmsGt0003 = segment.rmsAbove0003Frames / segment.frames.toFloat()
        val avgZcr = segment.zcrSum / segment.frames
        val avgFlatness = segment.flatnessSum / segment.frames
        val avgTemplateScore = segment.templateScoreSum / segment.frames
        val templateRatio = segment.templateFrames / segment.frames.toFloat()
        val rmsDynamicRange = segment.maxRms - segment.minRms.coerceAtMost(segment.maxRms)

        val sensitivityOffset = (config.sensitivity - 0.62f).coerceIn(-0.35f, 0.35f)
        val minDuration = (80L - (sensitivityOffset * 70f).toLong()).coerceAtLeast(40L)
        val dynamicRangeThreshold = (0.060f * (1f - sensitivityOffset * 0.35f)).coerceIn(0.030f, 0.10f)
        val active003Threshold = (0.010f * (1f - sensitivityOffset * 0.45f)).coerceIn(0.004f, 0.022f)
        val flatnessThreshold = (0.07f * (1f - sensitivityOffset * 0.28f)).coerceIn(0.040f, 0.12f)
        val zcrThreshold = (0.20f * (1f - sensitivityOffset * 0.18f)).coerceIn(0.16f, 0.26f)
        val maxSpeechRatio = (0.42f + sensitivityOffset * 0.12f).coerceIn(0.30f, 0.50f)

        val treeDecision = if (activeRatioRmsGt0001 < 0.518282f) {
            activeRatioRmsGt0003 >= active003Threshold
        } else {
            rmsDynamicRange >= dynamicRangeThreshold
        }
        val airflowDecision = segment.maxRms >= 0.001f &&
            segment.peak >= 0.003f &&
            avgZcr >= zcrThreshold &&
            avgFlatness >= flatnessThreshold &&
            activeRatio >= 0.25f
        val strongBroadbandDecision = segment.maxRms >= 0.010f &&
            segment.peak >= 0.020f &&
            avgZcr >= 0.18f &&
            activeRatio >= 0.25f
        val amplitudeRise = segment.maxRms - segment.minRms.coerceAtMost(segment.maxRms)
        val templateDecision = segment.maxTemplateScore >= 0.70f &&
            avgTemplateScore >= 0.54f &&
            templateRatio >= 0.45f &&
            activeRatio >= 0.45f &&
            segment.maxRms >= max(noiseFloor * 6.0f, 0.0060f) &&
            amplitudeRise >= max(noiseFloor * 2.5f, 0.0030f) &&
            segment.peak >= 0.0090f
        val durationOk = duration in minDuration..config.maximumDurationMillis
        val templateDurationOk = duration in 240L..560L
        val speechOk = speechRatio < maxSpeechRatio
        val triggered = templateDurationOk && speechOk && templateDecision

        val activeScore = when {
            templateDecision && (treeDecision || airflowDecision || strongBroadbandDecision) -> 1f
            templateDecision -> 0.92f
            treeDecision && (airflowDecision || strongBroadbandDecision) -> 1f
            treeDecision || airflowDecision || strongBroadbandDecision -> 0.84f
            else -> 0f
        }
        val durationScore = (duration / minDuration.toFloat()).coerceIn(0f, 1f)
        val speechPenalty = (speechRatio / maxSpeechRatio).coerceIn(0f, 1f) * 0.35f
        val confidence = (activeScore * 0.62f + durationScore * 0.18f + activeRatio * 0.08f + avgTemplateScore * 0.12f - speechPenalty)
            .coerceIn(0f, 1f)

        val reason = when {
            triggered && templateDecision -> "blow confirmed: template"
            triggered -> "blow confirmed: spectral segment"
            duration < 240L -> "candidate: too short"
            duration > 560L -> "too long"
            segment.maxRms < max(noiseFloor * 6.0f, 0.0060f) -> "candidate: below energy gate"
            amplitudeRise < max(noiseFloor * 2.5f, 0.0030f) -> "candidate: flat envelope"
            segment.peak < 0.0090f -> "candidate: weak peak"
            !speechOk -> "speech rejected"
            activeRatio < 0.45f -> "candidate: weak airflow"
            !templateDecision && !treeDecision && !airflowDecision && !strongBroadbandDecision -> "candidate: low spectral score"
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
        noiseFloor = noiseFloor.coerceIn(0.0006f, 0.18f)
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
        val spectral = spectralBandFeatures(samples, sampleRate)
        return BlowFeatures(
            rms = rms,
            peak = peak,
            zeroCrossingRate = crossings / samples.size.toFloat(),
            spectralCentroid = spectral.centroid,
            spectralFlatness = spectral.flatness,
            spectralTemplateScore = spectral.templateScore,
            frameEnergy = energy.toFloat(),
            clipping = peak > 0.97f
        )
    }

    private fun spectralBandFeatures(samples: ShortArray, sampleRate: Int): SpectralFeatures {
        val powers = TEMPLATE_FREQUENCIES
            .map { frequency -> frequency to if (frequency < sampleRate / 2f) goertzelPower(samples, sampleRate, frequency) else 0f }
        val total = powers.sumOf { it.second.toDouble() }.toFloat().coerceAtLeast(EPSILON)
        val centroid = powers.sumOf { (frequency, power) -> frequency.toDouble() * power.toDouble() }.toFloat() / total
        val geometric = exp(powers.sumOf { ln(it.second.coerceAtLeast(EPSILON)).toDouble() } / powers.size).toFloat()
        val arithmetic = total / powers.size
        return SpectralFeatures(
            centroid = centroid,
            flatness = (geometric / arithmetic.coerceAtLeast(EPSILON)).coerceIn(0f, 1f),
            templateScore = templateScore(powers.map { it.second })
        )
    }

    private fun templateScore(powers: List<Float>): Float {
        val dbValues = powers.map { power -> 10f * (ln(power.coerceAtLeast(EPSILON)) / LN_10).toFloat() }
        val peakDb = dbValues.maxOrNull() ?: return 0f
        var errorSum = 0f
        var insideCount = 0
        dbValues.forEachIndexed { index, db ->
            val normalizedDb = db - peakDb
            val expected = TEMPLATE_MEAN_DB[index]
            errorSum += abs(normalizedDb - expected)
            if (normalizedDb >= TEMPLATE_P10_DB[index] - 7f && normalizedDb <= TEMPLATE_P90_DB[index] + 7f) {
                insideCount += 1
            }
        }
        val curveScore = (1f - (errorSum / powers.size) / 24f).coerceIn(0f, 1f)
        val envelopeScore = insideCount / powers.size.toFloat()
        return (curveScore * 0.68f + envelopeScore * 0.32f).coerceIn(0f, 1f)
    }

    private fun goertzelPower(samples: ShortArray, sampleRate: Int, targetFrequency: Float): Float {
        val n = samples.size
        val k = (0.5f + n * targetFrequency / sampleRate).toInt()
        val omega = 2.0 * Math.PI * k / n
        val coefficient = (2.0 * cos(omega)).toFloat()
        var q0 = 0f
        var q1 = 0f
        var q2 = 0f
        samples.forEachIndexed { index, sample ->
            val window = if (n <= 1) 1f else (0.5f - 0.5f * cos(2.0 * Math.PI * index / (n - 1)).toFloat())
            val normalized = sample / Short.MAX_VALUE.toFloat()
            q0 = coefficient * q1 - q2 + normalized * window
            q2 = q1
            q1 = q0
        }
        return (q1 * q1 + q2 * q2 - coefficient * q1 * q2).coerceAtLeast(0f)
    }

    private fun speechConfidence(features: BlowFeatures): Float {
        val voicedZcr = (1f - abs(features.zeroCrossingRate - 0.07f) / 0.08f).coerceIn(0f, 1f)
        val voicedCentroid = (1f - abs(features.spectralCentroid - 450f) / 900f).coerceIn(0f, 1f)
        val lowFlatness = (1f - features.spectralFlatness).coerceIn(0f, 1f)
        return (voicedZcr * 0.48f + voicedCentroid * 0.22f + lowFlatness * 0.30f).coerceIn(0f, 1f)
    }

    private data class SpectralFeatures(
        val centroid: Float,
        val flatness: Float,
        val templateScore: Float
    )

    private data class Gates(
        val noiseGate: Float,
        val eventGate: Float,
        val continueGate: Float,
        val peakGate: Float,
        val templateStartGate: Float,
        val templateContinueGate: Float,
        val templateConfirmGate: Float
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
        var speechFrames: Int = 0,
        var activeFrames: Int = 0,
        var peak: Float = 0f,
        var maxRms: Float = 0f,
        var minRms: Float = Float.MAX_VALUE,
        var rmsAbove0001Frames: Int = 0,
        var rmsAbove0003Frames: Int = 0,
        var templateScoreSum: Float = 0f,
        var templateFrames: Int = 0,
        var maxTemplateScore: Float = 0f
    ) {
        val durationMillis: Long get() = lastActiveAt - startedAt

        fun add(features: BlowFeatures, speechConfidence: Float, active: Boolean, nowMillis: Long) {
            lastActiveAt = nowMillis
            frames += 1
            rmsSum += features.rms
            zcrSum += features.zeroCrossingRate
            flatnessSum += features.spectralFlatness
            templateScoreSum += features.spectralTemplateScore
            maxTemplateScore = max(maxTemplateScore, features.spectralTemplateScore)
            if (features.spectralTemplateScore >= 0.48f) templateFrames += 1
            peak = max(peak, features.peak)
            maxRms = max(maxRms, features.rms)
            minRms = min(minRms, features.rms)
            if (features.rms > 0.001f) rmsAbove0001Frames += 1
            if (features.rms > 0.003f) rmsAbove0003Frames += 1
            if (speechConfidence > 0.78f) speechFrames += 1
            if (active) activeFrames += 1
        }
    }

    private companion object {
        const val EPSILON = 0.000_000_001f
        const val LN_10 = 2.3025851f
        val TEMPLATE_FREQUENCIES = floatArrayOf(
            60.0f, 81.7f, 111.3f, 151.6f, 206.5f, 281.3f, 383.2f, 522.0f, 711.0f,
            968.4f, 1319.1f, 1796.8f, 2447.4f, 3333.6f, 4540.7f, 6185.0f, 7600.0f
        )
        val TEMPLATE_MEAN_DB = floatArrayOf(
            -9.27f, -8.49f, -6.75f, -5.82f, -8.74f, -13.76f, -19.55f, -23.96f, -29.15f,
            -37.85f, -39.84f, -44.98f, -47.76f, -55.81f, -55.08f, -58.01f, -54.46f
        )
        val TEMPLATE_P10_DB = floatArrayOf(
            -21.58f, -17.35f, -12.16f, -13.45f, -14.93f, -22.61f, -29.54f, -34.78f, -39.69f,
            -53.63f, -53.72f, -59.35f, -62.01f, -69.80f, -68.64f, -74.94f, -70.49f
        )
        val TEMPLATE_P90_DB = floatArrayOf(
            0.00f, -0.99f, -1.22f, -0.83f, -1.99f, -7.57f, -9.72f, -14.68f, -19.15f,
            -26.31f, -27.07f, -30.55f, -34.35f, -43.17f, -43.01f, -41.88f, -42.67f
        )
    }
}
