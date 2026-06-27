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
        cachedBounds = rootInActiveWindow?.let(::findLikelyNotificationBounds)
        diagnosticsRepository.updateBounds(cachedBounds)
        if (cachedBounds != null) {
            BlowAwayLog.d("accessibility bounds cached=$cachedBounds event=${event?.eventType}")
        }
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
            val coordinates = if (cachedBounds == null) {
                samsungFallbackSwipe(metrics.widthPixels, metrics.heightPixels, settings.gestureDurationMillis)
            } else {
                gesturePlanner.plan(
                    GestureRequest(
                        type = GestureType.SwipeUp,
                        bounds = cachedBounds,
                        durationMillis = settings.gestureDurationMillis.coerceIn(150, 250)
                    ),
                    Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
                )
            }
            dispatchSwipe(coordinates.startX, coordinates.startY, coordinates.endX, coordinates.endY, coordinates.durationMillis)
        }
    }
    fun dispatchDebugGesture(kind: String) {
        scope.launch {
            val metrics = resources.displayMetrics
            val width = metrics.widthPixels.toFloat()
            val height = metrics.heightPixels.toFloat()
            BlowAwayLog.i("debug gesture requested kind=$kind display=${metrics.widthPixels}x${metrics.heightPixels}")
            when (kind) {
                "up_deep" -> dispatchSwipe(width * 0.5f, height * 0.42f, width * 0.5f, height * 0.04f, 320)
                "up_top" -> dispatchSwipe(width * 0.5f, height * 0.24f, width * 0.5f, height * 0.02f, 260)
                "up_fast" -> dispatchSwipe(width * 0.5f, height * 0.30f, width * 0.5f, height * 0.01f, 150)
                "up_high" -> dispatchSwipe(width * 0.5f, height * 0.11f, width * 0.5f, 1f, 180)
                "left_high" -> dispatchSwipe(width * 0.9f, height * 0.11f, width * 0.05f, height * 0.11f, 220)
                "right_high" -> dispatchSwipe(width * 0.1f, height * 0.11f, width * 0.95f, height * 0.11f, 220)
                "left" -> dispatchSwipe(width * 0.88f, height * 0.14f, width * 0.08f, height * 0.14f, 260)
                "right" -> dispatchSwipe(width * 0.12f, height * 0.14f, width * 0.92f, height * 0.14f, 260)
                "back" -> {
                    val ok = performGlobalAction(GLOBAL_ACTION_BACK)
                    BlowAwayLog.i("debug global back dispatched=$ok")
                    diagnosticsRepository.updateGesture("global back: $ok")
                }
                else -> BlowAwayLog.w("unknown debug gesture kind=$kind")
            }
        }
    }


    private fun samsungFallbackSwipe(width: Int, height: Int, durationMillis: Long): com.blowaway.core.gesture.GestureCoordinates {
        val portrait = height >= width
        val startX = width * 0.5f
        val startY = if (portrait) height * 0.11f else height * 0.18f
        val endY = if (portrait) 1f else height * 0.03f
        return com.blowaway.core.gesture.GestureCoordinates(
            startX = startX,
            startY = startY,
            endX = startX,
            endY = endY,
            durationMillis = durationMillis.coerceIn(180, 240)
        )
    }

    private fun dispatchSwipe(rawStartX: Float, rawStartY: Float, rawEndX: Float, rawEndY: Float, durationMillis: Long) {
        val metrics = resources.displayMetrics
        val maxX = (metrics.widthPixels - 1).toFloat().coerceAtLeast(1f)
        val maxY = (metrics.heightPixels - 1).toFloat().coerceAtLeast(1f)
        val startX = rawStartX.coerceIn(1f, maxX)
        val startY = rawStartY.coerceIn(1f, maxY)
        val endX = rawEndX.coerceIn(1f, maxX)
        val endY = rawEndY.coerceIn(1f, maxY)
        BlowAwayLog.i(
            "dispatching gesture bounds=${cachedBounds ?: "fallback"} display=${metrics.widthPixels}x${metrics.heightPixels} start=${startX.toInt()},${startY.toInt()} end=${endX.toInt()},${endY.toInt()} duration=$durationMillis"
        )
        val gesture = try {
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMillis))
                .build()
        } catch (exception: IllegalArgumentException) {
            BlowAwayLog.w("gesture creation failed", exception)
            diagnosticsRepository.updateGesture("gesture invalid: ${exception.message}")
            return
        }
        dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    BlowAwayLog.i("gesture completed")
                    stateMachine.onGestureExecuted()
                    diagnosticsRepository.updateGesture(
                        "${startX.toInt()},${startY.toInt()} -> ${endX.toInt()},${endY.toInt()}"
                    )
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    BlowAwayLog.w("gesture cancelled")
                    diagnosticsRepository.updateGesture("gesture cancelled")
                }
            },
            null
        )
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