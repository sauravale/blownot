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
    private var detectorState: DetectorState = DetectorState.Idle
    private var smoothedConfidence = 0f

    override fun reset() {
        detectorState = DetectorState.Idle
        smoothedConfidence = 0f
    }

    override fun analyze(samples: ShortArray, sampleRate: Int, nowMillis: Long): DetectionResult {
        val config = configProvider()
        val features = extractFeatures(samples, sampleRate)
        val gates = gates(config)
        val rawSpeechConfidence = speechConfidence(features)
        val speechConfidence = if (features.rms < gates.eventGate) 0f else rawSpeechConfidence
        val state = detectorState
        val likelyEvent = features.rms > gates.noiseGate || state is DetectorState.Collecting
        updateNoiseFloor(features.rms, likelyEvent)

        if (state is DetectorState.Refractory) {
            if (nowMillis < state.untilMillis) {
                smoothedConfidence *= 0.80f
                return result(false, features, speechConfidence, "refractory")
            }
            detectorState = DetectorState.Idle
        }

        val activeFrame = isEventFrame(features, gates)
        return when (val current = detectorState) {
            DetectorState.Idle -> handleIdle(features, speechConfidence, gates, activeFrame, nowMillis)
            is DetectorState.Collecting -> handleCollecting(current, features, speechConfidence, gates, activeFrame, config, nowMillis)
            is DetectorState.Refractory -> result(false, features, speechConfidence, "refractory")
        }
    }

    private fun handleIdle(
        features: BlowFeatures,
        speechConfidence: Float,
        gates: Gates,
        activeFrame: Boolean,
        nowMillis: Long
    ): DetectionResult {
        if (!activeFrame) {
            smoothedConfidence *= 0.82f
            return result(
                triggered = false,
                features = features,
                speechConfidence = speechConfidence,
                reason = when {
                    features.rms < gates.noiseGate -> "ambient"
                    features.peak < gates.peakGate -> "noise rejected"
                    features.spectralTemplateScore < 0.42f -> "candidate: spectral mismatch"
                    else -> "below threshold"
                }
            )
        }

        val segment = BlowSegment(startedAt = nowMillis).also {
            it.add(features, speechConfidence, active = true, nowMillis = nowMillis)
        }
        detectorState = DetectorState.Collecting(segment)
        smoothedConfidence = smoothedConfidence * 0.70f + 0.18f
        return result(false, features, speechConfidence, "candidate: collecting")
    }

    private fun handleCollecting(
        current: DetectorState.Collecting,
        features: BlowFeatures,
        speechConfidence: Float,
        gates: Gates,
        activeFrame: Boolean,
        config: DetectionConfig,
        nowMillis: Long
    ): DetectionResult {
        val segment = current.segment
        val continuingFrame = activeFrame || isContinuationFrame(features, gates)
        if (continuingFrame) {
            segment.add(features, speechConfidence, active = activeFrame, nowMillis = nowMillis)
        } else {
            segment.addQuietFrame(nowMillis)
        }

        val quietGap = nowMillis - segment.lastActiveAt
        val overrun = segment.durationMillis > MAX_EVENT_MILLIS
        if (quietGap >= QUIET_GAP_MILLIS || overrun) {
            val decision = decide(segment, config)
            detectorState = if (decision.triggered) {
                DetectorState.Refractory(nowMillis + SHORT_REFRACTORY_MILLIS)
            } else {
                DetectorState.Idle
            }
            smoothedConfidence = smoothedConfidence * 0.54f + decision.confidence * 0.46f
            if (!decision.triggered) smoothedConfidence *= 0.76f
            return result(decision.triggered, features, speechConfidence, decision.reason, decision.debugSummary)
        }

        val live = liveDecision(segment)
        smoothedConfidence = smoothedConfidence * 0.72f + live.confidence * 0.28f
        return result(false, features, speechConfidence, live.reason, live.debugSummary)
    }

    private fun gates(config: DetectionConfig): Gates {
        val sensitivityOffset = (config.sensitivity - 0.62f).coerceIn(-0.35f, 0.35f)
        val noiseGate = max(noiseFloor * (1.55f - sensitivityOffset * 0.20f), 0.0008f)
        val eventGate = max(noiseFloor * (1.20f - sensitivityOffset * 0.16f), 0.0007f)
        val startGate = max(noiseFloor * (1.75f - sensitivityOffset * 0.20f), 0.0011f)
        val continueGate = max(noiseFloor * (1.45f - sensitivityOffset * 0.18f), 0.0012f)
        val peakGate = max(max(noiseFloor * 2.4f, 0.0025f), config.calibratedPeak * 0.007f * (1f - sensitivityOffset * 0.12f))
        return Gates(noiseGate, eventGate, startGate, continueGate, peakGate)
    }

    private fun isEventFrame(features: BlowFeatures, gates: Gates): Boolean {
        val energetic = features.rms >= gates.startGate && features.peak >= gates.peakGate
        val templateLift = features.spectralTemplateScore >= 0.58f &&
            features.rms >= max(noiseFloor * 2.35f, 0.0020f) &&
            features.peak >= max(noiseFloor * 3.0f, 0.0030f)
        return energetic || templateLift
    }

    private fun isContinuationFrame(features: BlowFeatures, gates: Gates): Boolean {
        val energetic = features.rms >= gates.continueGate && features.peak >= max(noiseFloor * 1.6f, 0.0018f)
        val templateTail = features.spectralTemplateScore >= 0.46f && features.rms >= gates.eventGate
        return energetic || templateTail
    }

    private fun liveDecision(segment: BlowSegment): SegmentDecision {
        val duration = segment.durationMillis
        val spectral = segment.peakWeightedTemplateScore
        val envelope = envelopeScore(segment.rmsValues)
        val confidence = (spectral * 0.52f + envelope * 0.28f + segment.energyScore(noiseFloor) * 0.20f).coerceIn(0f, 0.92f)
        val reason = when {
            duration < MIN_EVENT_MILLIS -> "candidate: collecting"
            spectral < 0.52f -> "candidate: spectral mismatch"
            envelope < 0.42f -> "candidate: envelope mismatch"
            else -> "candidate: possible blow"
        }
        return SegmentDecision(false, confidence, reason, segmentDebugSummary(segment, duration, spectral, envelope))
    }

    private fun decide(segment: BlowSegment, config: DetectionConfig): SegmentDecision {
        if (segment.frames == 0) return SegmentDecision(false, 0f, "candidate: empty", "frames=0")
        val duration = segment.durationMillis
        val sensitivityOffset = (config.sensitivity - 0.62f).coerceIn(-0.35f, 0.35f)
        val minDuration = (190L - (sensitivityOffset * 35f).toLong()).coerceIn(160L, 220L)
        val maxDuration = min(config.maximumDurationMillis.coerceAtMost(620L), (560L + (sensitivityOffset * 45f).toLong()).coerceIn(520L, 620L))
        val spectralScore = segment.peakWeightedTemplateScore
        val envelopeScore = envelopeScore(segment.rmsValues)
        val activeRatio = segment.activeFrames / segment.frames.toFloat()
        val speechRatio = segment.speechFrames / segment.frames.toFloat()
        val avgFlatness = segment.flatnessSum / segment.frames
        val avgZcr = segment.zcrSum / segment.frames
        val dynamicRange = segment.maxRms - segment.minRms.coerceAtMost(segment.maxRms)
        val energyOk = segment.maxRms >= max(noiseFloor * 5.2f, 0.0045f) &&
            segment.peak >= max(noiseFloor * 6.0f, 0.0070f)
        val riseOk = dynamicRange >= max(noiseFloor * 2.0f, 0.0025f)
        val durationOk = duration in minDuration..maxDuration
        val spectralOk = spectralScore >= (0.56f - sensitivityOffset * 0.04f) && segment.maxTemplateScore >= 0.68f
        val envelopeOk = envelopeScore >= (0.40f - sensitivityOffset * 0.04f)
        val toneLike = duration > 640L && avgFlatness < 0.018f && avgZcr < 0.075f && spectralScore < 0.58f && envelopeScore < 0.56f
        val speechOnly = (speechRatio > 0.30f && segment.maxRms < 0.08f) || (speechRatio > 0.72f && spectralScore < 0.66f && envelopeScore < 0.68f)
        val activeEnough = activeRatio >= 0.24f
        val triggered = durationOk && energyOk && riseOk && spectralOk && envelopeOk && !speechOnly && activeEnough
        val confidence = (
            spectralScore * 0.42f +
                envelopeScore * 0.30f +
                segment.energyScore(noiseFloor) * 0.16f +
                activeRatio.coerceIn(0f, 1f) * 0.12f -
                if (speechOnly) 0.25f else 0f -
                if (toneLike) 0.08f else 0f
            ).coerceIn(0f, 1f)
        val reason = when {
            triggered -> "blow confirmed: template event"
            duration < minDuration -> "candidate: too short"
            duration > maxDuration -> "candidate: too long"
            !energyOk -> "candidate: below energy gate"
            !riseOk -> "candidate: flat envelope"
            !spectralOk -> "candidate: spectral mismatch"
            !envelopeOk -> "candidate: envelope mismatch"
            toneLike -> "candidate: tone rejected"
            speechOnly -> "speech rejected"
            !activeEnough -> "candidate: weak airflow"
            else -> "candidate: rejected"
        }
        val debugSummary = segmentDebugSummary(
            segment = segment,
            duration = duration,
            spectralScore = spectralScore,
            envelopeScore = envelopeScore,
            extra = " minDuration=$minDuration maxDuration=$maxDuration energyOk=$energyOk riseOk=$riseOk spectralOk=$spectralOk envelopeOk=$envelopeOk speechOnly=$speechOnly activeEnough=$activeEnough avgZcr=${"%.3f".format(avgZcr)} avgFlatness=${"%.3f".format(avgFlatness)}"
        )
        return SegmentDecision(triggered, confidence, reason, debugSummary)
    }

    private fun segmentDebugSummary(
        segment: BlowSegment,
        duration: Long,
        spectralScore: Float,
        envelopeScore: Float,
        extra: String = ""
    ): String {
        val activeRatio = if (segment.frames == 0) 0f else segment.activeFrames / segment.frames.toFloat()
        val speechRatio = if (segment.frames == 0) 0f else segment.speechFrames / segment.frames.toFloat()
        val minRms = if (segment.minRms == Float.MAX_VALUE) 0f else segment.minRms
        return "duration=${duration}ms frames=${segment.frames} active=${"%.2f".format(activeRatio)} speech=${"%.2f".format(speechRatio)} spectral=${"%.2f".format(spectralScore)} maxTemplate=${"%.2f".format(segment.maxTemplateScore)} envelope=${"%.2f".format(envelopeScore)} maxRms=${"%.4f".format(segment.maxRms)} minRms=${"%.4f".format(minRms)} peak=${"%.4f".format(segment.peak)} noise=${"%.4f".format(noiseFloor)}$extra"
    }

    private fun envelopeScore(values: List<Float>): Float {
        if (values.size < 3) return 0f
        val peak = values.maxOrNull()?.coerceAtLeast(EPSILON) ?: return 0f
        var errorSum = 0f
        var inside = 0
        ENVELOPE_MEAN_DB.forEachIndexed { index, expected ->
            val position = if (ENVELOPE_MEAN_DB.size == 1) 0f else index / (ENVELOPE_MEAN_DB.size - 1).toFloat()
            val samplePosition = position * (values.size - 1)
            val lowIndex = samplePosition.toInt().coerceIn(0, values.lastIndex)
            val highIndex = (lowIndex + 1).coerceAtMost(values.lastIndex)
            val fraction = samplePosition - lowIndex
            val rms = values[lowIndex] * (1f - fraction) + values[highIndex] * fraction
            val db = 20f * (ln((rms / peak).coerceAtLeast(EPSILON)) / LN_10)
            errorSum += abs(db - expected)
            if (db >= ENVELOPE_P10_DB[index] - 9f && db <= ENVELOPE_P90_DB[index] + 9f) {
                inside += 1
            }
        }
        val curveScore = (1f - (errorSum / ENVELOPE_MEAN_DB.size) / 27f).coerceIn(0f, 1f)
        val bandScore = inside / ENVELOPE_MEAN_DB.size.toFloat()
        return (curveScore * 0.68f + bandScore * 0.32f).coerceIn(0f, 1f)
    }

    private fun result(triggered: Boolean, features: BlowFeatures, speechConfidence: Float, reason: String, debugSummary: String = ""): DetectionResult {
        return DetectionResult(
            triggered = triggered,
            confidence = smoothedConfidence.coerceIn(0f, 1f),
            speechConfidence = speechConfidence,
            noiseFloor = noiseFloor,
            features = features,
            reason = reason,
            debugSummary = debugSummary
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

    private sealed class DetectorState {
        data object Idle : DetectorState()
        data class Collecting(val segment: BlowSegment) : DetectorState()
        data class Refractory(val untilMillis: Long) : DetectorState()
    }

    private data class SpectralFeatures(
        val centroid: Float,
        val flatness: Float,
        val templateScore: Float
    )

    private data class Gates(
        val noiseGate: Float,
        val eventGate: Float,
        val startGate: Float,
        val continueGate: Float,
        val peakGate: Float
    )

    private data class SegmentDecision(
        val triggered: Boolean,
        val confidence: Float,
        val reason: String,
        val debugSummary: String = ""
    )

    private data class BlowSegment(
        val startedAt: Long,
        var lastActiveAt: Long = startedAt,
        var lastSeenAt: Long = startedAt,
        var frames: Int = 0,
        var zcrSum: Float = 0f,
        var flatnessSum: Float = 0f,
        var speechFrames: Int = 0,
        var activeFrames: Int = 0,
        var peak: Float = 0f,
        var maxRms: Float = 0f,
        var minRms: Float = Float.MAX_VALUE,
        var maxTemplateScore: Float = 0f,
        private val templateScores: MutableList<Float> = mutableListOf(),
        val rmsValues: MutableList<Float> = mutableListOf()
    ) {
        val durationMillis: Long get() = (lastActiveAt - startedAt + FRAME_MILLIS).coerceAtLeast(0L)
        val peakWeightedTemplateScore: Float
            get() {
                if (templateScores.isEmpty() || rmsValues.isEmpty()) return 0f
                val threshold = maxRms * 0.72f
                var weightedSum = 0f
                var weightSum = 0f
                templateScores.forEachIndexed { index, score ->
                    val rms = rmsValues.getOrElse(index) { 0f }
                    if (rms >= threshold || score >= maxTemplateScore - 0.04f) {
                        val weight = rms.coerceAtLeast(EPSILON)
                        weightedSum += score * weight
                        weightSum += weight
                    }
                }
                return if (weightSum <= 0f) templateScores.average().toFloat() else weightedSum / weightSum
            }

        fun add(features: BlowFeatures, speechConfidence: Float, active: Boolean, nowMillis: Long) {
            lastSeenAt = nowMillis
            lastActiveAt = nowMillis
            frames += 1
            zcrSum += features.zeroCrossingRate
            flatnessSum += features.spectralFlatness
            templateScores += features.spectralTemplateScore
            maxTemplateScore = max(maxTemplateScore, features.spectralTemplateScore)
            peak = max(peak, features.peak)
            maxRms = max(maxRms, features.rms)
            minRms = min(minRms, features.rms)
            rmsValues += features.rms
            if (speechConfidence > 0.78f) speechFrames += 1
            if (active) activeFrames += 1
        }

        fun addQuietFrame(nowMillis: Long) {
            lastSeenAt = nowMillis
        }


        fun energyScore(noiseFloor: Float): Float {
            val rmsScore = ((maxRms / max(noiseFloor * 8f, 0.008f)) - 0.45f).coerceIn(0f, 1f)
            val peakScore = ((peak / max(noiseFloor * 10f, 0.010f)) - 0.35f).coerceIn(0f, 1f)
            return (rmsScore * 0.58f + peakScore * 0.42f).coerceIn(0f, 1f)
        }
    }

    private companion object {
        const val EPSILON = 0.000_000_001f
        const val LN_10 = 2.3025851f
        const val FRAME_MILLIS = 20L
        const val MIN_EVENT_MILLIS = 220L
        const val MAX_EVENT_MILLIS = 760L
        const val QUIET_GAP_MILLIS = 80L
        const val SHORT_REFRACTORY_MILLIS = 180L
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
        val ENVELOPE_MEAN_DB = floatArrayOf(
            -40.98f, -40.41f, -39.58f, -38.96f, -37.97f, -37.20f, -36.36f, -35.22f,
            -33.78f, -32.19f, -30.48f, -28.12f, -25.83f, -23.17f, -20.88f, -19.40f,
            -17.34f, -16.26f, -14.51f, -12.63f, -11.00f, -8.87f, -7.26f, -6.51f,
            -5.07f, -3.81f, -3.54f, -3.18f, -3.02f, -2.99f, -3.09f, -3.19f,
            -3.36f, -3.50f, -3.83f, -4.50f, -5.18f, -5.57f, -6.14f, -6.76f,
            -7.14f, -7.79f, -8.60f, -9.42f, -10.54f, -11.06f, -11.73f, -13.08f,
            -13.86f, -16.09f, -17.19f, -18.25f, -19.60f, -20.44f, -21.81f, -22.97f,
            -24.56f, -25.55f, -26.84f, -28.61f, -29.12f, -29.36f, -30.53f, -31.91f
        )
        val ENVELOPE_P10_DB = floatArrayOf(
            -64.91f, -65.09f, -65.28f, -65.20f, -65.41f, -65.17f, -64.79f, -63.70f,
            -61.59f, -58.16f, -51.75f, -46.93f, -43.18f, -40.58f, -37.19f, -35.56f,
            -30.89f, -29.25f, -26.69f, -24.77f, -22.22f, -19.21f, -13.41f, -11.37f,
            -11.61f, -7.88f, -7.49f, -6.87f, -7.43f, -7.03f, -7.23f, -8.20f,
            -8.24f, -7.74f, -7.32f, -8.79f, -10.83f, -11.19f, -10.87f, -11.17f,
            -12.03f, -13.67f, -14.91f, -15.67f, -17.56f, -18.90f, -20.01f, -22.06f,
            -21.70f, -23.87f, -25.44f, -27.76f, -31.48f, -35.92f, -39.09f, -44.73f,
            -46.94f, -46.72f, -47.20f, -48.50f, -49.38f, -49.40f, -50.86f, -52.12f
        )
        val ENVELOPE_P90_DB = floatArrayOf(
            -19.15f, -16.58f, -15.59f, -13.09f, -13.04f, -11.77f, -10.35f, -10.29f,
            -10.04f, -8.99f, -9.31f, -7.75f, -7.20f, -7.72f, -6.83f, -6.56f,
            -6.77f, -6.27f, -4.50f, -3.72f, -2.60f, -2.04f, -1.57f, -0.62f,
            -0.85f, -1.19f, -0.51f, -0.11f, 0.00f, -0.26f, -0.00f, -0.05f,
            -0.08f, 0.00f, -0.29f, -1.07f, -1.15f, -1.24f, -1.78f, -2.45f,
            -2.94f, -2.97f, -2.28f, -2.72f, -3.04f, -4.64f, -4.96f, -5.76f,
            -6.57f, -6.82f, -8.21f, -7.59f, -8.12f, -9.79f, -10.76f, -11.14f,
            -13.30f, -13.29f, -13.89f, -14.85f, -14.17f, -12.06f, -10.51f, -12.27f
        )
    }
}
