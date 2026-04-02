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
 * Uses GLOBAL_ACTION_ANSWER_CALL (API 26+).
 * Only intercepts typeWindowStateChanged to avoid overloading.
 */
class SoloSafeAccessibilityService : AccessibilityService() {

    private var lastAnsweredTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        val isPhoneApp = pkg.contains("dialer") || pkg.contains("phone") ||
            pkg.contains("incallui") || pkg.contains("telecom")
        if (!isPhoneApp) return

        // Debounce
        val now = System.currentTimeMillis()
        if (now - lastAnsweredTime < 5000) return

        // Check ringing
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        @Suppress("DEPRECATION")
        if (tm?.callState != TelephonyManager.CALL_STATE_RINGING) return

        // Check PROTECTED state
        val prefs = getSharedPreferences(SoloSafeApp.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString("current_state", "standby") != "protected") return

        Log.d("SoloSafe", "Ringing + PROTECTED → auto-answer")

        // Answer call
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ok = performGlobalAction(11) // GLOBAL_ACTION_ANSWER_CALL
            if (ok) {
                lastAnsweredTime = now
                Log.d("SoloSafe", "AUTO-ANSWERED")
                return
            }
        }

        // Fallback: click answer button
        val root = rootInActiveWindow ?: return
        if (findAndClickAnswer(root)) {
            lastAnsweredTime = now
            Log.d("SoloSafe", "AUTO-ANSWERED via button")
        }
        root.recycle()
    }

    private fun findAndClickAnswer(node: AccessibilityNodeInfo): Boolean {
        val labels = listOf("rispondi", "answer", "accept", "accetta")
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        for (l in labels) {
            if (text.contains(l) || desc.contains(l)) {
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
                node.parent?.let { p ->
                    if (p.isClickable) { p.performAction(AccessibilityNodeInfo.ACTION_CLICK); p.recycle(); return true }
                    p.recycle()
                }
            }
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            if (findAndClickAnswer(c)) { c.recycle(); return true }
            c.recycle()
        }
        return false
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("SoloSafe", "AccessibilityService CONNECTED")
    }
}
