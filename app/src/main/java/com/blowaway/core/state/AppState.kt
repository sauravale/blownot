package com.blowaway.core.state

enum class AppState {
    Idle,
    NotificationActive,
    ListeningForBlow,
    BlowConfirmed,
    SwipeGestureExecuted,
    Cooldown
}
