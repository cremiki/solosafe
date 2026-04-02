package com.solosafe.app.utils

import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager

/**
 * Checks OEM-specific call capabilities and known issues.
 * Used to determine if GSM native calls work reliably on this device.
 */
object DeviceCallCapability {

    data class Capability(
        val canMakeGsmCalls: Boolean = true,
        val canSendSms: Boolean = true,
        val needsTwilioFallback: Boolean = false,
        val oemIssue: String? = null,
    )

    // Known OEM issues with background calling (from app_config blacklist)
    private val OEM_BLACKLIST = mapOf(
        "xiaomi" to "Background call restricted by MIUI",
        "oppo" to "Background call restricted by ColorOS",
        "vivo" to "Background call restricted by FuntouchOS",
    )

    fun check(context: Context): Capability {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val hasTelephony = context.getSystemService(Context.TELEPHONY_SERVICE) != null
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val hasSim = tm?.simState == TelephonyManager.SIM_STATE_READY

        val oemIssue = OEM_BLACKLIST[manufacturer]

        return Capability(
            canMakeGsmCalls = hasTelephony && hasSim && oemIssue == null,
            canSendSms = hasTelephony && hasSim,
            needsTwilioFallback = oemIssue != null || !hasSim,
            oemIssue = oemIssue,
        )
    }
}
