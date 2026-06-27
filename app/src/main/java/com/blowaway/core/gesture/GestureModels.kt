package com.blowaway.core.gesture

import android.graphics.Rect

enum class GestureType {
    SwipeUp,
    SwipeDown,
    SwipeLeft,
    SwipeRight,
    Tap,
    LongPress
}

data class GestureRequest(
    val type: GestureType,
    val bounds: Rect?,
    val durationMillis: Long
)

data class GestureCoordinates(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val durationMillis: Long
)
