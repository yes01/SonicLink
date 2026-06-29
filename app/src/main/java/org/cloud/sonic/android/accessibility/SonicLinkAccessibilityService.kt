package org.cloud.sonic.android.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.cloud.sonic.android.utils.SLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

class SonicLinkAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        SonicLinkAccessibilityState.service = this
        SLog.i("SonicLink accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        SLog.w("SonicLink accessibility service interrupted")
    }

    override fun onDestroy() {
        SonicLinkAccessibilityState.service = null
        super.onDestroy()
    }

    fun tap(x: Float, y: Float): SonicLinkControlResult {
        unsupportedGestures()?.let { return it }
        return gesture(listOf(stroke(linePath(x, y, x, y), 0L, TAP_DURATION_MS)))
    }

    fun longPress(x: Float, y: Float, durationMs: Long): SonicLinkControlResult {
        unsupportedGestures()?.let { return it }
        return gesture(listOf(stroke(linePath(x, y, x, y), 0L, max(durationMs, LONG_PRESS_MIN_MS))))
    }

    fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): SonicLinkControlResult {
        unsupportedGestures()?.let { return it }
        return gesture(listOf(stroke(linePath(startX, startY, endX, endY), 0L, max(durationMs, 1L))))
    }

    fun multiTouch(points: List<SonicLinkTouchStroke>): SonicLinkControlResult {
        unsupportedGestures()?.let { return it }
        if (points.isEmpty()) {
            return SonicLinkControlResult.failure("invalid_payload", "multi_touch requires at least one stroke")
        }
        val strokes = points.map {
            stroke(linePath(it.startX, it.startY, it.endX, it.endY), it.startTimeMs, max(it.durationMs, 1L))
        }
        return gesture(strokes)
    }

    fun globalAction(actionName: String): SonicLinkControlResult {
        val action = when (actionName.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents", "recent_apps" -> GLOBAL_ACTION_RECENTS
            "notifications", "notification" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power_dialog", "power_menu" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                GLOBAL_ACTION_POWER_DIALOG
            } else {
                null
            }
            else -> null
        } ?: return SonicLinkControlResult.failure("unsupported_action", "global action is not supported: $actionName")

        return if (performGlobalAction(action)) {
            SonicLinkControlResult.success()
        } else {
            SonicLinkControlResult.failure("action_failed", "global action failed: $actionName")
        }
    }

    fun setText(text: String): SonicLinkControlResult {
        val node = findEditableNode(rootInActiveWindow)
            ?: return SonicLinkControlResult.failure("no_editable_node", "no editable input node is focused")
        return setNodeText(node, text)
    }

    fun inputKey(actionName: String): SonicLinkControlResult {
        val node = findEditableNode(rootInActiveWindow)
            ?: return SonicLinkControlResult.failure("no_editable_node", "no editable input node is focused")
        return when (actionName.lowercase()) {
            "enter" -> appendText(node, "\n")
            "delete", "backspace" -> deleteBackward(node)
            "select_all" -> selectAll(node)
            else -> SonicLinkControlResult.failure("unsupported_action", "input key is not supported: $actionName")
        }
    }

    private fun gesture(strokes: List<GestureDescription.StrokeDescription>): SonicLinkControlResult {
        val latch = CountDownLatch(1)
        var result = SonicLinkControlResult.failure("gesture_timeout", "gesture timed out")
        val gesture = GestureDescription.Builder().apply {
            strokes.forEach { addStroke(it) }
        }.build()

        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    result = SonicLinkControlResult.success()
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    result = SonicLinkControlResult.failure("gesture_cancelled", "gesture was cancelled")
                    latch.countDown()
                }
            },
            null
        )
        if (!dispatched) {
            return SonicLinkControlResult.failure("gesture_dispatch_failed", "gesture dispatch returned false")
        }
        latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return result
    }

    private fun unsupportedGestures(): SonicLinkControlResult? {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            SonicLinkControlResult.failure("unsupported_api", "gesture dispatch requires Android 7.0+")
        } else {
            null
        }
    }

    private fun linePath(startX: Float, startY: Float, endX: Float, endY: Float): Path {
        return Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
    }

    private fun stroke(path: Path, startTimeMs: Long, durationMs: Long): GestureDescription.StrokeDescription {
        return GestureDescription.StrokeDescription(path, startTimeMs, durationMs)
    }

    private fun findEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) {
            return null
        }
        if (node.isFocused && node.isEditable) {
            return node
        }
        for (index in 0 until node.childCount) {
            val found = findEditableNode(node.getChild(index))
            if (found != null) {
                return found
            }
        }
        return null
    }

    private fun appendText(node: AccessibilityNodeInfo, value: String): SonicLinkControlResult {
        val currentText = node.text?.toString().orEmpty()
        return setNodeText(node, currentText + value)
    }

    private fun deleteBackward(node: AccessibilityNodeInfo): SonicLinkControlResult {
        val currentText = node.text?.toString().orEmpty()
        if (currentText.isEmpty()) {
            return SonicLinkControlResult.success()
        }
        return setNodeText(node, currentText.dropLast(1))
    }

    private fun selectAll(node: AccessibilityNodeInfo): SonicLinkControlResult {
        val textLength = node.text?.length ?: 0
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, textLength)
        }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)) {
            SonicLinkControlResult.success()
        } else {
            SonicLinkControlResult.failure("select_all_failed", "failed to select all text")
        }
    }

    private fun setNodeText(node: AccessibilityNodeInfo, text: String): SonicLinkControlResult {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            SonicLinkControlResult.success()
        } else {
            SonicLinkControlResult.failure("set_text_failed", "failed to set text on current input node")
        }
    }

    companion object {
        private const val TAP_DURATION_MS = 60L
        private const val LONG_PRESS_MIN_MS = 500L
        private const val GESTURE_TIMEOUT_MS = 10_000L
    }
}

data class SonicLinkTouchStroke(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val startTimeMs: Long,
    val durationMs: Long
)
