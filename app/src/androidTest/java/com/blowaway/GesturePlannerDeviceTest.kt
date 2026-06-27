package com.blowaway

import android.graphics.Rect
import com.blowaway.core.gesture.GesturePlanner
import com.blowaway.core.gesture.GestureRequest
import com.blowaway.core.gesture.GestureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GesturePlannerDeviceTest {
    @Test
    fun plansSwipeUpFromNotificationCenter() {
        val planner = GesturePlanner()
        val coordinates = planner.plan(
            GestureRequest(GestureType.SwipeUp, Rect(10, 20, 310, 220), 200),
            Rect(0, 0, 400, 300)
        )
        assertEquals(160f, coordinates.startX)
        assertEquals(120f, coordinates.startY)
        assertTrue(coordinates.endY < coordinates.startY)
        assertEquals(200, coordinates.durationMillis)
    }
}
