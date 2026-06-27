package com.blowaway.core.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StateMachine @Inject constructor() {
    private val mutableState = MutableStateFlow(AppState.Idle)
    val state: StateFlow<AppState> = mutableState

    fun onHeadsUpDetected() {
        mutableState.value = AppState.NotificationActive
    }

    fun onListeningStarted() {
        if (mutableState.value == AppState.NotificationActive) {
            mutableState.value = AppState.ListeningForBlow
        }
    }

    fun onBlowConfirmed() {
        mutableState.value = AppState.BlowConfirmed
    }

    fun onGestureExecuted() {
        mutableState.value = AppState.SwipeGestureExecuted
    }

    fun onCooldown() {
        mutableState.value = AppState.Cooldown
    }

    fun onIdle() {
        mutableState.value = AppState.Idle
    }
}
