package com.blowaway.core.state

enum class AppState {
    Idle,
    DebugMicMonitor,
    NotificationActive,
    ListeningForBlow,
    BlowConfirmed,
    DismissalRequested,
    SwipeGestureExecuted,
    Cooldown
}

