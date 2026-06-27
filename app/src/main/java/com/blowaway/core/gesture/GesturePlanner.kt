package com.blowaway.core.gesture

import android.graphics.Rect
import javax.inject.Inject

class GesturePlanner @Inject constructor() {
    fun plan(request: GestureRequest, fallbackBounds: Rect): GestureCoordinates {
        val bounds = request.bounds?.takeUnless { it.isEmpty } ?: fallbackBounds
        val centerX = bounds.exactCenterX()
        val centerY = bounds.exactCenterY()
        val verticalDistance = bounds.height().coerceAtLeast(120) * 0.75f
        val horizontalDistance = bounds.width().coerceAtLeast(120) * 0.75f
        return when (request.type) {
            GestureType.SwipeUp -> GestureCoordinates(centerX, centerY, centerX, centerY - verticalDistance, request.durationMillis)
            GestureType.SwipeDown -> GestureCoordinates(centerX, centerY, centerX, centerY + verticalDistance, request.durationMillis)
            GestureType.SwipeLeft -> GestureCoordinates(centerX, centerY, centerX - horizontalDistance, centerY, request.durationMillis)
            GestureType.SwipeRight -> GestureCoordinates(centerX, centerY, centerX + horizontalDistance, centerY, request.durationMillis)
            GestureType.Tap -> GestureCoordinates(centerX, centerY, centerX, centerY, 1)
            GestureType.LongPress -> GestureCoordinates(centerX, centerY, centerX, centerY, request.durationMillis.coerceAtLeast(500))
        }
    }
}
