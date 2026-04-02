package com.solosafe.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.solosafe.app.SoloSafeApp

/**
 * Auto-answers incoming calls from authorized numbers via Accessibility Service.
 * No need to be default phone app — works by finding and clicking the "Answer" button.
 * Active ONLY when app is in PROTECTED state and caller is authorized.
 */
class SoloSafeAccessibilityService : AccessibilityService() {

    private var lastAnsweredTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        // Only act when phone is ringing
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (tm?.callState != TelephonyManager.CALL_STATE_RINGING) return

        // Debounce — don't answer twice within 5s
        val now = System.currentTimeMillis()
        if (now - lastAnsweredTime < 5000) return

        // Check if in PROTECTED state
        val prefs = getSharedPreferences(SoloSafeApp.PREFS_NAME, Context.MODE_PRIVATE)
        val isProtected = prefs.getString("current_state", "standby") == "protected"
        if (!isProtected) return

        // Try to find and click the answer button
        val rootNode = rootInActiveWindow ?: return
        if (findAndClickAnswer(rootNode)) {
            lastAnsweredTime = now
            Log.d("SoloSafe", "Auto-answered call via Accessibility Service")
        }
        rootNode.recycle()
    }

    private fun findAndClickAnswer(node: AccessibilityNodeInfo): Boolean {
        // Common answer button labels across Android phones
        val answerLabels = listOf(
            "rispondi", "answer", "accept", "accetta",
            "risposta", "slide to answer", "scorri per rispondere",
        )

        // Check this node
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val className = node.className?.toString() ?: ""

        for (label in answerLabels) {
            if (text.contains(label) || desc.contains(label)) {
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d("SoloSafe", "Clicked answer button: text='$text' desc='$desc'")
                    return true
                }
                // Try clicking parent if node isn't clickable
                val parent = node.parent
                if (parent?.isClickable == true) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    parent.recycle()
                    Log.d("SoloSafe", "Clicked parent of answer button: text='$text'")
                    return true
                }
                parent?.recycle()
            }
        }

        // Recurse children
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

    override fun onInterrupt() {
        Log.d("SoloSafe", "AccessibilityService interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("SoloSafe", "SoloSafe AccessibilityService connected")
    }
}
