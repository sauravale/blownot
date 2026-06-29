package com.blowaway.core.detection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeuristicBlowDetectorTest {
    @Test
    fun confirmsTemplateShapedBlowAfterCandidateEnds() {
        val detector = HeuristicBlowDetector {
            DetectionConfig(
                sensitivity = 0.8f,
                cooldownMillis = 2_000,
                calibratedRms = 0.08f,
                calibratedPeak = 0.28f,
                calibratedCentroid = 3_500f
            )
        }
        var triggered = false
        repeat(24) { frame ->
            val result = detector.analyze(templateBlowFrame(frame), 16_000, frame * 20L)
            triggered = triggered || result.triggered
        }
        triggered = triggered || detector.analyze(quietFrame(), 16_000, 620L).triggered
        assertTrue(triggered)
    }

    @Test
    fun rejectsVoiceLikeLowFrequencyInput() {
        val detector = HeuristicBlowDetector { DetectionConfig(sensitivity = 0.9f) }
        var triggered = false
        repeat(16) { frame ->
            val result = detector.analyze(voiceLikeFrame(), 16_000, frame * 20L)
            triggered = triggered || result.triggered
        }
        assertFalse(triggered)
    }


    @Test
    fun rejectsLowAmplitudeBroadbandNoise() {
        val detector = HeuristicBlowDetector {
            DetectionConfig(
                sensitivity = 0.85f,
                cooldownMillis = 2_000,
                calibratedRms = 0.06f,
                calibratedPeak = 0.20f,
                calibratedCentroid = 2_400f
            )
        }
        var triggered = false
        repeat(24) { frame ->
            val result = detector.analyze(lowAmplitudeAirflowFrame(frame), 16_000, frame * 20L)
            triggered = triggered || result.triggered
        }
        assertFalse(triggered)
    }

    @Test
    fun confirmsShortTemplateShapedBlowAfterCandidateEnds() {
        val detector = HeuristicBlowDetector {
            DetectionConfig(sensitivity = 0.8f, cooldownMillis = 2_000, calibratedRms = 0.08f)
        }
        var triggered = false
        repeat(10) { frame ->
            triggered = triggered || detector.analyze(templateBlowFrame(frame + 5), 16_000, frame * 20L).triggered
        }
        triggered = triggered || detector.analyze(quietFrame(), 16_000, 300L).triggered
        assertTrue(triggered)
    }

    @Test
    fun rejectsSustainedTemplateShapedAirflow() {
        val detector = HeuristicBlowDetector {
            DetectionConfig(sensitivity = 0.8f, cooldownMillis = 2_000, calibratedRms = 0.08f)
        }
        var triggered = false
        repeat(50) { frame ->
            triggered = triggered || detector.analyze(templateBlowFrame(12), 16_000, frame * 20L).triggered
        }
        triggered = triggered || detector.analyze(quietFrame(), 16_000, 1_100L).triggered
        assertFalse(triggered)
    }
    @Test
    fun respectsCooldownAfterTrigger() {
        val detector = HeuristicBlowDetector {
            DetectionConfig(sensitivity = 0.8f, cooldownMillis = 2_000, calibratedRms = 0.08f)
        }
        repeat(24) { frame -> detector.analyze(templateBlowFrame(frame), 16_000, frame * 20L) }
        detector.analyze(quietFrame(), 16_000, 620L)
        val duringCooldown = detector.analyze(templateBlowFrame(0), 16_000, 620)
        assertFalse(duringCooldown.triggered)
    }

    private fun templateBlowFrame(frame: Int): ShortArray {
        val frequencies = floatArrayOf(60.0f, 81.7f, 111.3f, 151.6f, 206.5f, 281.3f, 383.2f, 522.0f, 711.0f, 968.4f, 1319.1f, 1796.8f, 2447.4f, 3333.6f, 4540.7f, 6185.0f, 7600.0f)
        val db = floatArrayOf(-9.27f, -8.49f, -6.75f, -5.82f, -8.74f, -13.76f, -19.55f, -23.96f, -29.15f, -37.85f, -39.84f, -44.98f, -47.76f, -55.81f, -55.08f, -58.01f, -54.46f)
        return ShortArray(320) { index ->
            val t = index / 16_000.0
            var sample = 0.0
            frequencies.forEachIndexed { i, frequency ->
                val amplitude = Math.pow(10.0, db[i] / 20.0) * blowEnvelope(frame)
                sample += amplitude * kotlin.math.sin(2.0 * Math.PI * frequency * t + frame * 0.13)
            }
            (sample * 18_000.0).coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort()
        }
    }

    private fun blowEnvelope(frame: Int): Double {
        val db = doubleArrayOf(
            -30.0, -25.0, -20.0, -15.0, -11.0, -8.0, -6.0, -4.5,
            -3.5, -3.0, -3.0, -3.2, -3.8, -4.8, -6.5, -8.8,
            -11.5, -14.5, -18.0, -21.5, -25.0, -28.0, -31.0, -34.0
        )
        return Math.pow(10.0, db[frame.coerceIn(db.indices)] / 20.0)
    }
    private fun quietFrame(): ShortArray = ShortArray(320) { 0 }

    private fun lowAmplitudeAirflowFrame(frame: Int): ShortArray {
        return ShortArray(320) { index ->
            val active = frame in 2..10
            val amplitude = if (active) 120 + ((frame + index) % 5) * 38 else 18
            val sign = if ((index + frame) % 2 == 0) 1 else -1
            (sign * amplitude).toShort()
        }
    }
    private fun voiceLikeFrame(): ShortArray {
        return ShortArray(320) { index ->
            val sign = if ((index / 18) % 2 == 0) 1 else -1
            (sign * 5_000).toShort()
        }
    }
}


