package com.blowaway.core.detection

import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TfliteBlowDetector(
    modelBuffer: ByteBuffer,
    private val fallback: BlowDetector,
    private val configProvider: () -> DetectionConfig
) : BlowDetector {
    private val interpreter = Interpreter(modelBuffer)
    private var lastTriggerAt = Long.MIN_VALUE / 4

    override fun analyze(samples: ShortArray, sampleRate: Int, nowMillis: Long): DetectionResult {
        val fallbackResult = fallback.analyze(samples, sampleRate, nowMillis)
        val input = toModelInput(samples)
        val output = Array(1) { FloatArray(LABELS.size) }
        interpreter.run(input, output)
        val blowConfidence = output[0][LABELS.indexOf("Blow")]
        val speechConfidence = output[0][LABELS.indexOf("Speech")]
        val config = configProvider()
        val canTrigger = nowMillis - lastTriggerAt >= config.cooldownMillis
        val triggered = blowConfidence >= 0.82f && output[0].withIndex().maxBy { it.value }.index == 0 && canTrigger
        if (triggered) {
            lastTriggerAt = nowMillis
        }
        return fallbackResult.copy(
            triggered = triggered,
            confidence = blowConfidence,
            speechConfidence = speechConfidence,
            reason = if (triggered) "tflite blow confirmed" else "tflite rejected"
        )
    }

    fun close() {
        interpreter.close()
    }

    private fun toModelInput(samples: ShortArray): ByteBuffer {
        val targetSamples = 16_000
        val buffer = ByteBuffer.allocateDirect(targetSamples * java.lang.Float.BYTES).order(ByteOrder.nativeOrder())
        repeat(targetSamples) { index ->
            val sample = samples.getOrNull(index) ?: 0
            buffer.putFloat(sample / Short.MAX_VALUE.toFloat())
        }
        buffer.rewind()
        return buffer
    }

    companion object {
        val LABELS = listOf("Blow", "Speech", "Cough", "Wind", "Fan", "TV", "Music", "Keyboard", "Traffic", "Silence")
    }
}
