package com.blowaway.service

import com.blowaway.core.detection.BlowFeatures
import com.blowaway.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.max

@Singleton
class CalibrationRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val diagnosticsRepository: DiagnosticsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val completedSegments = mutableListOf<CalibrationSegment>()
    private var active = false
    private var segmentStartedAt: Long? = null
    private var segmentLastLoudAt: Long = 0
    private var rmsSum = 0f
    private var centroidSum = 0f
    private var zcrSum = 0f
    private var flatnessSum = 0f
    private var frames = 0
    private var peak = 0f

    fun start() {
        completedSegments.clear()
        clearSegment()
        active = true
        diagnosticsRepository.updateCalibration("Calibration active: blow 5 times")
    }

    fun stop() {
        active = false
        clearSegment()
        diagnosticsRepository.updateCalibration("Calibration stopped")
    }

    fun observe(features: BlowFeatures, nowMillis: Long) {
        if (!active) return
        val loudEnough = features.rms > 0.035f && features.peak > 0.08f
        if (loudEnough) {
            if (segmentStartedAt == null) {
                segmentStartedAt = nowMillis
                rmsSum = 0f
                centroidSum = 0f
                frames = 0
                peak = 0f
            }
            segmentLastLoudAt = nowMillis
            rmsSum += features.rms
            centroidSum += features.spectralCentroid
            zcrSum += features.zeroCrossingRate
            flatnessSum += features.spectralFlatness
            frames += 1
            peak = max(peak, features.peak)
            diagnosticsRepository.updateCalibration("Capturing blow ${completedSegments.size + 1}/5 RMS %.3f peak %.3f ZCR %.3f flat %.2f".format(features.rms, features.peak, features.zeroCrossingRate, features.spectralFlatness))
            return
        }

        val startedAt = segmentStartedAt ?: return
        if (nowMillis - segmentLastLoudAt < 140) return

        val duration = segmentLastLoudAt - startedAt
        if (duration in 120..1_000 && frames > 0) {
            completedSegments += CalibrationSegment(
                rms = rmsSum / frames,
                peak = peak,
                durationMillis = duration,
                centroid = centroidSum / frames,
                zcr = zcrSum / frames,
                flatness = flatnessSum / frames
            )
            diagnosticsRepository.updateCalibration("Captured ${completedSegments.size}/5 blows")
            if (completedSegments.size >= 5) {
                finish()
            }
        }
        clearSegment()
    }

    private fun finish() {
        val segments = completedSegments.toList()
        active = false
        scope.launch {
            val current = settingsRepository.settings.first()
            settingsRepository.update(
                current.copy(
                    averageRms = segments.map { it.rms }.average().toFloat().coerceAtLeast(0.02f),
                    averageDurationMillis = segments.map { it.durationMillis }.average().toLong(),
                    peakAmplitude = segments.map { it.peak }.average().toFloat().coerceAtLeast(0.05f),
                    spectralCentroid = segments.map { it.centroid }.average().toFloat().coerceAtLeast(600f)
                )
            )
            diagnosticsRepository.updateCalibration(
                "Saved: RMS %.3f, peak %.3f, duration %d ms, ZCR %.3f, flat %.2f".format(segments.map { it.rms }.average().toFloat(), segments.map { it.peak }.average().toFloat(), segments.map { it.durationMillis }.average().toLong(), segments.map { it.zcr }.average().toFloat(), segments.map { it.flatness }.average().toFloat())
            )
        }
    }

    private fun clearSegment() {
        segmentStartedAt = null
        rmsSum = 0f
        centroidSum = 0f
        zcrSum = 0f
        flatnessSum = 0f
        frames = 0
        peak = 0f
    }

    private data class CalibrationSegment(
        val rms: Float,
        val peak: Float,
        val durationMillis: Long,
        val centroid: Float,
        val zcr: Float,
        val flatness: Float
    )
}
