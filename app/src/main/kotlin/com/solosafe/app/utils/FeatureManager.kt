package com.solosafe.app.utils

import android.content.Context
import com.solosafe.app.SoloSafeApp

object FeatureManager {
    fun isPro(context: Context): Boolean {
        val prefs = context.getSharedPreferences(SoloSafeApp.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(SoloSafeApp.KEY_OPERATOR_ID, null) != null
    }

    fun isFree(context: Context): Boolean = !isPro(context)

    fun canSendSmsForAlarm(type: String): Boolean {
        return type == "SOS"
    }

    fun canSendExternalNotification(context: Context, type: String): Boolean {
        return isPro(context) || type == "SOS"
    }

    fun canUseAutoDetector(context: Context): Boolean {
        return isPro(context)
    }

    fun heartbeatInterval(context: Context): Long {
        return if (isPro(context)) 5 * 60 * 1000L else 30 * 60 * 1000L
    }
}
