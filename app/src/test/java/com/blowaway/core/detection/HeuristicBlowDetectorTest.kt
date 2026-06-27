package com.blowaway.core.detection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeuristicBlowDetectorTest {
    @Test
    fun confirmsBroadbandBlowAfterMinimumDuration() {
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
        repeat(12) { frame ->
            val result = detector.analyze(blowLikeFrame(), 16_000, frame * 20L)
            triggered = triggered || result.triggered
        }
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
    fun respectsCooldownAfterTrigger() {
        val detector = HeuristicBlowDetector {
            DetectionConfig(sensitivity = 0.8f, cooldownMillis = 2_000, calibratedRms = 0.08f)
        }
        repeat(12) { frame -> detector.analyze(blowLikeFrame(), 16_000, frame * 20L) }
        val duringCooldown = detector.analyze(blowLikeFrame(), 16_000, 260)
        assertFalse(duringCooldown.triggered)
    }

    private fun blowLikeFrame(): ShortArray {
        return ShortArray(320) { index ->
            val sign = if ((index / 2) % 2 == 0) 1 else -1
            (sign * (4_200 + (index % 17) * 90)).toShort()
        }
    }

    private fun voiceLikeFrame(): ShortArray {
        return ShortArray(320) { index ->
            val sign = if ((index / 18) % 2 == 0) 1 else -1
            (sign * 5_000).toShort()
        }
    }
}
