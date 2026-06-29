package com.blowaway.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.blowaway.core.detection.BlowDetector
import com.blowaway.core.detection.BlowFeatures
import com.blowaway.core.detection.DetectionResult
import com.blowaway.core.state.AppState
import com.blowaway.core.state.StateMachine
import com.blowaway.data.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MicrophoneForegroundService : Service() {
    @Inject lateinit var detector: BlowDetector
    @Inject lateinit var stateMachine: StateMachine
    @Inject lateinit var diagnosticsRepository: DiagnosticsRepository
    @Inject lateinit var recordingLabRepository: RecordingLabRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        BlowAwayLog.i("microphone foreground service created")
        createChannel()
        startForeground(42, notification())
        observeState()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        BlowAwayLog.i("microphone foreground service destroyed")
        scope.cancel()
        super.onDestroy()
    }

    private fun observeState() {
        scope.launch {
            stateMachine.state.collectLatest { state ->
                diagnosticsRepository.updateState(state)
                BlowAwayLog.d("service observed state=$state")
                if (state == AppState.DebugMicMonitor) {
                    listenUntilStateChanges(allowDismissal = false, timeoutMillis = null)
                }
                if (state == AppState.NotificationActive || state == AppState.ListeningForBlow) {
                    val timeoutMillis = settingsRepository.settings.first().listeningWindowMillis.coerceIn(1_000, 10_000)
                    stateMachine.onListeningStarted()
                    listenUntilStateChanges(allowDismissal = true, timeoutMillis = timeoutMillis)
                }
            }
        }
    }

    private suspend fun listenUntilStateChanges(allowDismissal: Boolean, timeoutMillis: Long?) {
        val startedAt = System.currentTimeMillis()
        val session = if (allowDismissal) ListeningSession(startedAt) else null
        detector.reset()
        BlowAwayLog.i("audio loop starting allowDismissal=$allowDismissal timeoutMillis=$timeoutMillis")
        val sampleRate = 16_000
        val frameSize = sampleRate / 50
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(frameSize * 2)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer
        )
        val buffer = ShortArray(frameSize)
        try {
            recorder.startRecording()
            while (stateMachine.state.value == AppState.ListeningForBlow || stateMachine.state.value == AppState.DebugMicMonitor) {
                val nowMillis = System.currentTimeMillis()
                if (shouldStopListening(allowDismissal, startedAt, nowMillis, timeoutMillis)) {
                    stateMachine.onIdle()
                    break
                }
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val samples = buffer.copyOf(read)
                    val result = analyzeFrame(samples, sampleRate, nowMillis, session)
                    diagnosticsRepository.updateAudio(
                        features = result.features,
                        confidence = result.confidence,
                        speechConfidence = result.speechConfidence,
                        noiseFloor = result.noiseFloor,
                        reason = result.reason,
                        waveform = samples.take(96).map { it / Short.MAX_VALUE.toFloat() }
                    )
                    recordingLabRepository.observeSamples(
                        samples = samples,
                        sampleRate = sampleRate,
                        features = result.features,
                        triggered = result.triggered,
                        confidence = result.confidence,
                        speechConfidence = result.speechConfidence,
                        noiseFloor = result.noiseFloor,
                        reason = result.reason,
                        nowMillis = nowMillis
                    )
                    if (shouldLogResult(result)) {
                        BlowAwayLog.d(
                            "detector reason=${result.reason} trigger=${result.triggered} confidence=${"%.2f".format(result.confidence)} speech=${"%.2f".format(result.speechConfidence)} rms=${"%.3f".format(result.features.rms)} noise=${"%.3f".format(result.noiseFloor)} peak=${"%.3f".format(result.features.peak)} tmpl=${"%.2f".format(result.features.spectralTemplateScore)} zcr=${"%.3f".format(result.features.zeroCrossingRate)} centroid=${"%.0f".format(result.features.spectralCentroid)} flatness=${"%.2f".format(result.features.spectralFlatness)}"
                        )
                    }
                    if (result.triggered && allowDismissal) {
                        BlowAwayLog.i("detector triggered dismissal")
                        stateMachine.onBlowConfirmed()
                        stateMachine.onDismissalRequested()
                        val dismissalRequested = AccessibilityBridge.dismissHeadsUp()
                        if (dismissalRequested) {
                            stateMachine.onCooldown()
                            delay(2_000)
                        } else {
                            diagnosticsRepository.updateGesture("accessibility service unavailable")
                        }
                        stateMachine.onIdle()
                    }
                } else {
                    delay(20)
                }
            }
        } finally {
            BlowAwayLog.i("audio loop stopping state=${stateMachine.state.value}")
            detector.reset()
            recorder.stop()
            recorder.release()
        }
    }

    private fun shouldStopListening(
        allowDismissal: Boolean,
        startedAt: Long,
        nowMillis: Long,
        timeoutMillis: Long?
    ): Boolean {
        if (!allowDismissal || timeoutMillis == null) return false
        val elapsed = nowMillis - startedAt
        val headsUpVisible = AccessibilityBridge.hasVisibleHeadsUp(nowMillis, HEADS_UP_VISIBILITY_GRACE_MILLIS)
        if (elapsed >= HARD_LISTENING_CAP_MILLIS) {
            BlowAwayLog.i("audio loop hard timed out after ${elapsed}ms headsUpVisible=$headsUpVisible")
            return true
        }
        if (elapsed >= timeoutMillis && !headsUpVisible) {
            BlowAwayLog.i("audio loop stopping after ${elapsed}ms because heads-up is no longer visible")
            return true
        }
        return false
    }

    private fun analyzeFrame(
        samples: ShortArray,
        sampleRate: Int,
        nowMillis: Long,
        session: ListeningSession?
    ): DetectionResult {
        if (session == null) {
            return detector.analyze(samples, sampleRate, nowMillis)
        }

        val basicFeatures = extractBasicFeatures(samples)
        val decision = session.onFrame(basicFeatures, nowMillis)
        if (decision.resetDetector) {
            detector.reset()
        }
        if (!decision.analyze) {
            return DetectionResult(
                triggered = false,
                confidence = 0f,
                speechConfidence = 0f,
                noiseFloor = decision.alertRms,
                features = basicFeatures,
                reason = decision.reason
            )
        }
        return detector.analyze(samples, sampleRate, nowMillis)
    }

    private fun shouldLogResult(result: DetectionResult): Boolean {
        return result.reason != "ambient" && result.reason != "below threshold"
    }

    private fun extractBasicFeatures(samples: ShortArray): BlowFeatures {
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
        return BlowFeatures(
            rms = sqrt(energy / samples.size).toFloat(),
            peak = peak,
            zeroCrossingRate = crossings / samples.size.toFloat(),
            spectralCentroid = 0f,
            spectralFlatness = 0f,
            frameEnergy = energy.toFloat(),
            clipping = peak > 0.97f,
            spectralTemplateScore = 0f
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "BlowAway listening", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("BlowAway is ready")
            .setContentText("Microphone processing runs locally only while a heads-up notification is active.")
            .setOngoing(true)
            .build()
    }

    private class ListeningSession(private val startedAt: Long) {
        private var phase: Phase = Phase.Settling
        private var alertRmsSum = 0f
        private var alertPeak = 0f
        private var alertPeakSum = 0f
        private var alertFrames = 0
        private var alertRms = 0.0012f
        private var recentRms = 0.0012f
        private var analysisWindowUntil = Long.MIN_VALUE
        private var armedLogged = false
        private var lastSessionReason = ""

        fun onFrame(features: BlowFeatures, nowMillis: Long): GateDecision {
            val elapsed = nowMillis - startedAt
            if (elapsed < SETTLING_MILLIS) {
                updateRecent(features)
                return decision(false, true, "session: settling", features)
            }

            if (elapsed < SETTLING_MILLIS + CALIBRATION_MILLIS) {
                phase = Phase.CalibratingAlertBed
                collectAlertBed(features)
                updateRecent(features)
                return decision(false, true, "session: calibrating alert bed", features)
            }

            if (phase != Phase.Armed) {
                phase = Phase.Armed
                alertRms = (alertRmsSum / alertFrames.coerceAtLeast(1)).coerceAtLeast(0.0008f)
                val averagePeak = alertPeakSum / alertFrames.coerceAtLeast(1)
                alertPeak = min(alertPeak, max(averagePeak * 2.2f, 0.006f))
                recentRms = alertRms
                if (!armedLogged) {
                    armedLogged = true
                    BlowAwayLog.i("listening session armed alertRms=${"%.4f".format(alertRms)} alertPeak=${"%.4f".format(alertPeak)}")
                }
                return decision(false, true, "session: armed", features)
            }

            val transient = looksLikeTransientAboveAlertBed(features)
            if (transient && nowMillis > analysisWindowUntil) {
                analysisWindowUntil = nowMillis + ANALYSIS_WINDOW_MILLIS
                updateRecent(features)
                return decision(true, true, "session: transient analysis", features)
            }

            updateRecent(features)
            if (nowMillis <= analysisWindowUntil) {
                return decision(true, false, "session: transient analysis", features)
            }

            val reason = if (features.rms > max(alertRms * 1.20f, 0.0025f)) {
                "session: ongoing alert sound"
            } else {
                "session: armed"
            }
            return decision(false, true, reason, features)
        }

        private fun collectAlertBed(features: BlowFeatures) {
            alertRmsSum += features.rms
            alertPeak = max(alertPeak, features.peak)
            alertPeakSum += features.peak
            alertFrames += 1
        }

        private fun looksLikeTransientAboveAlertBed(features: BlowFeatures): Boolean {
            val rmsGate = max(max(alertRms * 1.30f, recentRms * 1.25f), alertRms + 0.0015f).coerceAtLeast(0.0025f)
            val peakGate = max(max(alertPeak * 1.10f, 0.0060f), alertPeak + 0.0020f)
            val energeticRise = features.rms >= rmsGate && features.peak >= peakGate
            val airflowLike = features.zeroCrossingRate >= 0.10f || features.peak >= max(alertPeak * 1.35f, 0.008f)
            return energeticRise && airflowLike
        }

        private fun updateRecent(features: BlowFeatures) {
            recentRms = recentRms * 0.90f + features.rms * 0.10f
        }

        private fun decision(analyze: Boolean, resetDetector: Boolean, reason: String, features: BlowFeatures): GateDecision {
            if (reason != lastSessionReason) {
                BlowAwayLog.d("listening session reason=$reason rms=${"%.3f".format(features.rms)} alert=${"%.3f".format(alertRms)} peak=${"%.3f".format(features.peak)} zcr=${"%.3f".format(features.zeroCrossingRate)}")
                lastSessionReason = reason
            }
            return GateDecision(analyze, resetDetector, reason, alertRms)
        }

        private enum class Phase {
            Settling,
            CalibratingAlertBed,
            Armed
        }

        companion object {
            private const val SETTLING_MILLIS = 350L
            private const val CALIBRATION_MILLIS = 250L
            private const val ANALYSIS_WINDOW_MILLIS = 1_200L
        }
    }

    private data class GateDecision(
        val analyze: Boolean,
        val resetDetector: Boolean,
        val reason: String,
        val alertRms: Float
    )

    private companion object {
        const val CHANNEL_ID = "blowaway_listening"
        const val HEADS_UP_VISIBILITY_GRACE_MILLIS = 900L
        const val HARD_LISTENING_CAP_MILLIS = 12_000L
    }
}





