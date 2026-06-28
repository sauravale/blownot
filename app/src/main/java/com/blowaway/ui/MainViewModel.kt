package com.blowaway.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blowaway.data.settings.AppSettings
import com.blowaway.data.settings.SettingsRepository
import com.blowaway.core.state.StateMachine
import com.blowaway.service.AccessibilityBridge
import com.blowaway.service.DiagnosticsRepository
import com.blowaway.service.DiagnosticsState
import com.blowaway.service.RecordingLabRepository
import com.blowaway.service.RecordingLabState
import com.blowaway.service.TestNotificationPoster
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val testNotificationPoster: TestNotificationPoster,
    private val stateMachine: StateMachine,
    private val recordingLabRepository: RecordingLabRepository,
    diagnosticsRepository: DiagnosticsRepository
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppSettings()
    )

    val diagnostics: StateFlow<DiagnosticsState> = diagnosticsRepository.diagnostics
    val recordingLab: StateFlow<RecordingLabState> = recordingLabRepository.state

    fun updateSettings(settings: AppSettings) {
        viewModelScope.launch {
            settingsRepository.update(settings)
        }
    }

    fun postTestNotification() {
        testNotificationPoster.post()
    }

    fun startDebugMicMonitor() {
        stateMachine.onDebugMicMonitorStarted()
    }

    fun stopDebugMicMonitor() {
        stateMachine.onIdle()
    }


    fun startLabRecording(label: String) {
        recordingLabRepository.start(label)
        stateMachine.onDebugMicMonitorStarted()
    }

    fun stopLabRecording() {
        recordingLabRepository.stop()
    }

    fun clearLabRecordings() {
        recordingLabRepository.clear()
    }

    fun exportLabArchive(): File = recordingLabRepository.exportArchive()

    fun dispatchDebugGesture(kind: String) {
        AccessibilityBridge.dispatchDebugGesture(kind)
    }

    fun dismissActivePopup() {
        AccessibilityBridge.dismissHeadsUp()
    }
}
