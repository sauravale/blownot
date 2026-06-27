package com.blowaway.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blowaway.data.settings.AppSettings
import com.blowaway.data.settings.SettingsRepository
import com.blowaway.service.DiagnosticsRepository
import com.blowaway.service.DiagnosticsState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    diagnosticsRepository: DiagnosticsRepository
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppSettings()
    )

    val diagnostics: StateFlow<DiagnosticsState> = diagnosticsRepository.diagnostics

    fun updateSettings(settings: AppSettings) {
        viewModelScope.launch {
            settingsRepository.update(settings)
        }
    }
}
