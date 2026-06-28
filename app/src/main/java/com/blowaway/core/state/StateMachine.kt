package com.blowaway.core.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.blowaway.service.BlowAwayLog
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StateMachine @Inject constructor() {
    private val mutableState = MutableStateFlow(AppState.Idle)
    val state: StateFlow<AppState> = mutableState

    fun onHeadsUpDetected() {
        when (mutableState.value) {
            AppState.ListeningForBlow -> {
                BlowAwayLog.i("state ListeningForBlow kept active after additional notification")
            }
            AppState.NotificationActive -> {
                BlowAwayLog.i("state NotificationActive -> ListeningForBlow after refreshed notification")
                mutableState.value = AppState.ListeningForBlow
            }
            else -> {
                BlowAwayLog.i("state ${mutableState.value} -> NotificationActive")
                mutableState.value = AppState.NotificationActive
            }
        }
    }

    fun onDebugMicMonitorStarted() {
        BlowAwayLog.i("state ${mutableState.value} -> DebugMicMonitor")
        mutableState.value = AppState.DebugMicMonitor
    }

    fun onListeningStarted() {
        if (mutableState.value == AppState.NotificationActive) {
            BlowAwayLog.i("state NotificationActive -> ListeningForBlow")
            mutableState.value = AppState.ListeningForBlow
        }
    }

    fun onBlowConfirmed() {
        BlowAwayLog.i("state ${mutableState.value} -> BlowConfirmed")
        mutableState.value = AppState.BlowConfirmed
    }


    fun onDismissalRequested() {
        BlowAwayLog.i("state ${mutableState.value} -> DismissalRequested")
        mutableState.value = AppState.DismissalRequested
    }
    fun onGestureExecuted() {
        BlowAwayLog.i("state ${mutableState.value} -> SwipeGestureExecuted")
        mutableState.value = AppState.SwipeGestureExecuted
    }

    fun onCooldown() {
        BlowAwayLog.i("state ${mutableState.value} -> Cooldown")
        mutableState.value = AppState.Cooldown
    }

    fun onIdle() {
        BlowAwayLog.i("state ${mutableState.value} -> Idle")
        mutableState.value = AppState.Idle
    }
}

