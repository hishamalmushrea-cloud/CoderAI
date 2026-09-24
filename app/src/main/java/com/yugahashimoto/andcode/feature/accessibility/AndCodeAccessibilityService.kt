package com.yugahashimoto.andcode.feature.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Android Accessibility Service that allows the AI Phone Agent to interact with screen elements:
 * tapping buttons (e.g. WhatsApp send button), reading visible text, typing text, and performing
 * gestures without needing root access or ADB wireless debugging.
 */
class AndCodeAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AndCode Accessibility Service connected")

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Track active window or package if necessary
    }

    override fun onInterrupt() {
        Log.w(TAG, "AndCode Accessibility Service interrupted")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * Searches for a node matching [textOrId] (case-insensitive) and triggers a click action.
     */
    fun clickElementByTextOrId(textOrId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val trimmed = textOrId.trim()

        // 1. Search by view ID
        val byId = root.findAccessibilityNodeInfosByViewId(trimmed)
        for (node in byId) {
            if (performClickOnNodeOrParent(node)) return true
        }

        // 2. Search by text content
        val byText = root.findAccessibilityNodeInfosByText(trimmed)
        for (node in byText) {
            if (performClickOnNodeOrParent(node)) return true
        }

        // 3. Recursive fuzzy search
        return searchAndClickRecursive(root, trimmed.lowercase())
    }

    private fun searchAndClickRecursive(node: AccessibilityNodeInfo?, target: String): Boolean {
        if (node == null) return false
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val resId = node.viewIdResourceName?.lowercase() ?: ""

        if (text.contains(target) || desc.contains(target) || resId.contains(target)) {
            if (performClickOnNodeOrParent(node)) return true
        }

        for (i in 0 until node.childCount) {
            if (searchAndClickRecursive(node.getChild(i), target)) return true
        }
        return false
    }

    private fun performClickOnNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        return false
    }

    /**
     * Injects text into currently focused input field.
     */
    fun typeIntoFocusedElement(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /**
     * Automatically finds and clicks WhatsApp's send button after a short delay (e.g. 1.5 seconds)
     * Supports both English ("Send") and Arabic ("إرسال") interfaces.
     */
    fun scheduleClickSend(delayMs: Long = 1500) {
        mainHandler.postDelayed({
            runCatching {
                val root = rootInActiveWindow ?: return@postDelayed
                val sendLabels = listOf("send", "إرسال", "ارسال", "btn_send", "send_button")
                for (label in sendLabels) {
                    if (clickElementByTextOrId(label)) {
                        Log.i(TAG, "Successfully auto-clicked send button for: $label")
                        return@postDelayed
                    }
                }
                // Try searching for WhatsApp's specific send button view ID
                val sendNodes = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
                for (node in sendNodes) {
                    if (performClickOnNodeOrParent(node)) {
                        Log.i(TAG, "Clicked com.whatsapp:id/send button")
                        return@postDelayed
                    }
                }
            }
        }, delayMs)
    }

    /**
     * Dumps UI hierarchy into a list of element maps with coordinates, text, and IDs.
     */
    fun dumpCurrentScreen(): List<Map<String, Any>> {
        val list = mutableListOf<Map<String, Any>>()
        val root = rootInActiveWindow ?: return list
        dumpNodeRecursive(root, list)
        return list
    }

    private fun dumpNodeRecursive(node: AccessibilityNodeInfo?, list: dynamicList) {
        if (node == null || !node.isVisibleToUser) return
        val rect = Rect()
        node.getBoundsInScreen(rect)

        val map = mutableMapOf<String, Any>(
            "className" to (node.className?.toString() ?: ""),
            "clickable" to node.isClickable,
            "bounds" to listOf(rect.left, rect.top, rect.right, rect.bottom)
        )
        node.text?.let { map["text"] = it.toString() }
        node.contentDescription?.let { map["contentDescription"] = it.toString() }
        node.viewIdResourceName?.let { map["viewId"] = it }

        if (map.containsKey("text") || map.containsKey("contentDescription") || node.isClickable) {
            list.add(map)
        }

        for (i in 0 until node.childCount) {
            dumpNodeRecursive(node.getChild(i), list)
        }
    }

    companion object {
        private const val TAG = "AndCodeAccessibility"

        @Volatile
        var instance: AndCodeAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }
}

private typealias dynamicList = MutableList<Map<String, Any>>
