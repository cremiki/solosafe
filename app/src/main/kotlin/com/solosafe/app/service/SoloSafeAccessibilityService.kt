package com.solosafe.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.solosafe.app.SoloSafeApp

/**
 * Auto-answers incoming calls when in PROTECTED state.
 *
 * Strategy 1 (Android 8+): GLOBAL_ACTION_ANSWER_CALL — official API, most reliable
 * Strategy 2 (fallback): Find and click the "Answer" button in the call UI
 */
class SoloSafeAccessibilityService : AccessibilityService() {

    private var lastAnsweredTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Log all events for debugging
        val eventType = AccessibilityEvent.eventTypeToString(event.eventType)
        val pkg = event.packageName?.toString() ?: "null"

        // Only care about phone/dialer apps
        val isPhoneApp = pkg.contains("dialer") || pkg.contains("phone") ||
            pkg.contains("incallui") || pkg.contains("telecom")
        if (!isPhoneApp) return

        Log.d("SoloSafe", "AccessibilityEvent: type=$eventType pkg=$pkg")

        // Debounce
        val now = System.currentTimeMillis()
        if (now - lastAnsweredTime < 5000) return

        // Check if phone is ringing
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        @Suppress("DEPRECATION")
        val isRinging = tm?.callState == TelephonyManager.CALL_STATE_RINGING
        if (!isRinging) {
            Log.d("SoloSafe", "Phone not ringing, skip")
            return
        }

        // Check if in PROTECTED state
        val prefs = getSharedPreferences(SoloSafeApp.PREFS_NAME, Context.MODE_PRIVATE)
        val currentState = prefs.getString("current_state", "standby")
        if (currentState != "protected") {
            Log.d("SoloSafe", "Not PROTECTED (state=$currentState), skip auto-answer")
            return
        }

        Log.d("SoloSafe", "Phone ringing + PROTECTED → attempting auto-answer...")

        // Strategy 1: GLOBAL_ACTION_ANSWER_CALL (Android 8+, most reliable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // GLOBAL_ACTION_ANSWER_CALL = 11 (added in API 26)
            val success = performGlobalAction(11)
            if (success) {
                lastAnsweredTime = now
                Log.d("SoloSafe", "AUTO-ANSWERED via GLOBAL_ACTION_ANSWER_CALL")
                return
            }
            Log.w("SoloSafe", "GLOBAL_ACTION_ANSWER_CALL failed, trying button click...")
        }

        // Strategy 2: Find and click answer button
        val rootNode = rootInActiveWindow
        if (rootNode != null) {
            dumpNodeTree(rootNode, 0) // Debug: log the entire UI tree
            if (findAndClickAnswer(rootNode)) {
                lastAnsweredTime = now
                Log.d("SoloSafe", "AUTO-ANSWERED via button click")
            }
            rootNode.recycle()
        } else {
            Log.w("SoloSafe", "rootInActiveWindow is null")
        }
    }

    private fun findAndClickAnswer(node: AccessibilityNodeInfo): Boolean {
        val answerLabels = listOf(
            "rispondi", "answer", "accept", "accetta", "risposta",
            "slide to answer", "scorri per rispondere", "swipe to answer",
            // Blackview / stock Android
            "answer call", "rispondere alla chiamata",
        )

        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        for (label in answerLabels) {
            if (text.contains(label) || desc.contains(label)) {
                // Try click on node
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d("SoloSafe", "Clicked: text='$text' desc='$desc'")
                    return true
                }
                // Try parent
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.d("SoloSafe", "Clicked parent of: text='$text'")
                        parent.recycle()
                        return true
                    }
                    val next = parent.parent
                    parent.recycle()
                    parent = next
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickAnswer(child)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    /** Debug: dump the UI node tree to logcat */
    private fun dumpNodeTree(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 5) return // limit depth
        val indent = "  ".repeat(depth)
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val cls = node.className?.toString()?.substringAfterLast(".") ?: ""
        val clickable = if (node.isClickable) "[CLICK]" else ""
        if (text.isNotEmpty() || desc.isNotEmpty() || node.isClickable) {
            Log.d("SoloSafe", "${indent}$cls $clickable text='$text' desc='$desc'")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNodeTree(child, depth + 1)
            child.recycle()
        }
    }

    override fun onInterrupt() {
        Log.d("SoloSafe", "AccessibilityService interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("SoloSafe", "SoloSafe AccessibilityService CONNECTED and active")
    }
}
