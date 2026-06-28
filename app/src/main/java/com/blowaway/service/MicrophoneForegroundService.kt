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
import com.blowaway.core.state.AppState
import com.blowaway.core.state.StateMachine
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MicrophoneForegroundService : Service() {
    @Inject lateinit var detector: BlowDetector
    @Inject lateinit var stateMachine: StateMachine
    @Inject lateinit var diagnosticsRepository: DiagnosticsRepository
    @Inject lateinit var calibrationRepository: CalibrationRepository
    @Inject lateinit var recordingLabRepository: RecordingLabRepository

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
                    listenUntilStateChanges(allowDismissal = false)
                }
                if (state == AppState.NotificationActive || state == AppState.ListeningForBlow) {
                    stateMachine.onListeningStarted()
                    listenUntilStateChanges(allowDismissal = true)
                }
            }
        }
    }

    private suspend fun listenUntilStateChanges(allowDismissal: Boolean) {
        BlowAwayLog.i("audio loop starting allowDismissal=$allowDismissal")
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
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val samples = buffer.copyOf(read)
                    val result = detector.analyze(samples, sampleRate, System.currentTimeMillis())
                    diagnosticsRepository.updateAudio(
                        features = result.features,
                        confidence = result.confidence,
                        speechConfidence = result.speechConfidence,
                        noiseFloor = result.noiseFloor,
                        reason = result.reason,
                        waveform = samples.take(96).map { it / Short.MAX_VALUE.toFloat() }
                    )
                    val nowMillis = System.currentTimeMillis()
                    calibrationRepository.observe(result.features, nowMillis = nowMillis)
                    recordingLabRepository.observeSamples(
                        samples = samples,
                        sampleRate = sampleRate,
                        features = result.features,
                        confidence = result.confidence,
                        speechConfidence = result.speechConfidence,
                        noiseFloor = result.noiseFloor,
                        reason = result.reason,
                        nowMillis = nowMillis
                    )
                    if (result.reason != "ambient" && result.reason != "below threshold") {
                        BlowAwayLog.d(
                            "detector reason=${result.reason} trigger=${result.triggered} confidence=${"%.2f".format(result.confidence)} speech=${"%.2f".format(result.speechConfidence)} rms=${"%.3f".format(result.features.rms)} noise=${"%.3f".format(result.noiseFloor)} peak=${"%.3f".format(result.features.peak)} zcr=${"%.3f".format(result.features.zeroCrossingRate)} centroid=${"%.0f".format(result.features.spectralCentroid)} flatness=${"%.2f".format(result.features.spectralFlatness)}"
                        )
                    }
                    if (result.triggered && allowDismissal) {
                        BlowAwayLog.i("detector triggered dismissal")
                        stateMachine.onBlowConfirmed()
                        val dismissed = AccessibilityBridge.dismissHeadsUp()
                        if (!dismissed) {
                            diagnosticsRepository.updateGesture("accessibility service unavailable")
                        }
                        stateMachine.onCooldown()
                        delay(2_000)
                        stateMachine.onIdle()
                    }
                } else {
                    delay(20)
                }
            }
        } finally {
            BlowAwayLog.i("audio loop stopping state=${stateMachine.state.value}")
            recorder.stop()
            recorder.release()
        }
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

    private companion object {
        const val CHANNEL_ID = "blowaway_listening"
    }
}
