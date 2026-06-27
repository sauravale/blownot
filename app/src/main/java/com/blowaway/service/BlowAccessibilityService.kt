package com.blowaway.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.blowaway.core.gesture.GesturePlanner
import com.blowaway.core.gesture.GestureRequest
import com.blowaway.core.gesture.GestureType
import com.blowaway.core.state.StateMachine
import com.blowaway.data.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BlowAccessibilityService : AccessibilityService() {
    @Inject lateinit var gesturePlanner: GesturePlanner
    @Inject lateinit var stateMachine: StateMachine
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var diagnosticsRepository: DiagnosticsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var cachedBounds: Rect? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityBridge.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        cachedBounds = findLikelyNotificationBounds(root)
        diagnosticsRepository.updateBounds(cachedBounds)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        AccessibilityBridge.detach(this)
        scope.cancel()
        super.onDestroy()
    }

    fun dismissHeadsUp() {
        scope.launch {
            val settings = settingsRepository.settings.first()
            val metrics = resources.displayMetrics
            val fallback = Rect(0, 0, metrics.widthPixels, (metrics.heightPixels * 0.28f).toInt())
            val coordinates = gesturePlanner.plan(
                GestureRequest(
                    type = GestureType.SwipeUp,
                    bounds = cachedBounds,
                    durationMillis = settings.gestureDurationMillis.coerceIn(150, 250)
                ),
                fallback
            )
            val path = Path().apply {
                moveTo(coordinates.startX, coordinates.startY)
                lineTo(coordinates.endX, coordinates.endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, coordinates.durationMillis))
                .build()
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        stateMachine.onGestureExecuted()
                        diagnosticsRepository.updateGesture(
                            "${coordinates.startX.toInt()},${coordinates.startY.toInt()} -> ${coordinates.endX.toInt()},${coordinates.endY.toInt()}"
                        )
                    }
                },
                null
            )
        }
    }

    private fun findLikelyNotificationBounds(root: AccessibilityNodeInfo): Rect? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val candidate = Rect()
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            node.getBoundsInScreen(candidate)
            val nearTop = candidate.top >= 0 && candidate.top < resources.displayMetrics.heightPixels * 0.35f
            val wideEnough = candidate.width() > resources.displayMetrics.widthPixels * 0.5f
            val usefulHeight = candidate.height() in 48..420
            val hasText = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
            if (nearTop && wideEnough && usefulHeight && hasText) {
                return Rect(candidate)
            }
            repeat(node.childCount) { index ->
                node.getChild(index)?.let(queue::add)
            }
        }
        return null
    }
}
