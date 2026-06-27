package com.blowaway.service

import android.graphics.Rect
import com.blowaway.core.detection.BlowFeatures
import com.blowaway.core.state.AppState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class DiagnosticsState(
    val rms: Float = 0f,
    val noiseFloor: Float = 0f,
    val blowConfidence: Float = 0f,
    val speechConfidence: Float = 0f,
    val currentApplication: String = "",
    val currentNotificationKey: String = "",
    val accessibilityBounds: Rect? = null,
    val gestureCoordinates: String = "",
    val appState: AppState = AppState.Idle,
    val cooldownMillisRemaining: Long = 0,
    val totalDismissals: Int = 0,
    val falseTriggers: Int = 0,
    val missedDetections: Int = 0,
    val waveform: List<Float> = emptyList()
)

@Singleton
class DiagnosticsRepository @Inject constructor() {
    private val mutableDiagnostics = MutableStateFlow(DiagnosticsState())
    val diagnostics: StateFlow<DiagnosticsState> = mutableDiagnostics

    fun updateAudio(features: BlowFeatures, confidence: Float, speechConfidence: Float, waveform: List<Float>) {
        mutableDiagnostics.update {
            it.copy(
                rms = features.rms,
                noiseFloor = features.rms * 0.4f,
                blowConfidence = confidence,
                speechConfidence = speechConfidence,
                waveform = waveform
            )
        }
    }

    fun updateNotification(packageName: String, key: String) {
        mutableDiagnostics.update { it.copy(currentApplication = packageName, currentNotificationKey = key) }
    }

    fun updateBounds(bounds: Rect?) {
        mutableDiagnostics.update { it.copy(accessibilityBounds = bounds) }
    }

    fun updateGesture(description: String) {
        mutableDiagnostics.update { it.copy(gestureCoordinates = description, totalDismissals = it.totalDismissals + 1) }
    }

    fun updateState(state: AppState) {
        mutableDiagnostics.update { it.copy(appState = state) }
    }
}
