package com.solosafe.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.solosafe.app.MainActivity
import com.solosafe.app.R
import com.solosafe.app.SoloSafeApp.Companion.CHANNEL_ALARM
import com.solosafe.app.SoloSafeApp.Companion.CHANNEL_SERVICE
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SoloSafeService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_STANDBY -> startStandby()
            ACTION_START_PROTECTED -> startProtected(
                preset = intent.getStringExtra(EXTRA_PRESET) ?: "WAREHOUSE",
                sessionEnd = intent.getStringExtra(EXTRA_SESSION_END),
            )
            ACTION_STOP -> stopSelf()
            ACTION_SOS -> triggerSos()
            ACTION_PLACE_CALL -> placeCall(intent.getStringExtra(EXTRA_PHONE) ?: return START_STICKY)
        }
        return START_STICKY
    }

    private fun startStandby() {
        acquireWakeLock()
        val notification = buildNotification(
            title = "SoloSafe — In standby",
            text = "SOS sempre disponibile",
            ongoing = true,
        )
        startForegroundCompat(notification)
    }

    private fun startProtected(preset: String, sessionEnd: String?) {
        acquireWakeLock()
        val endText = sessionEnd?.let { " — Fine: $it" } ?: ""
        val notification = buildNotification(
            title = "🟢 SoloSafe — Protetto",
            text = "Preset: $preset$endText",
            ongoing = true,
            addSosAction = true,
            addDrivingAction = true,
        )
        startForegroundCompat(notification)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun triggerSos() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_solosafe)
            .setContentTitle("🚨 SOS ATTIVO")
            .setContentText("Allarme inviato — soccorsi in arrivo")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setVibrate(longArrayOf(0, 500, 200, 500, 200, 500))
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_SOS, notification)
    }

    private fun buildNotification(
        title: String,
        text: String,
        ongoing: Boolean,
        addSosAction: Boolean = false,
        addDrivingAction: Boolean = false,
    ): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_solosafe)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(ongoing)
            .setSilent(true)

        if (addSosAction) {
            val sosIntent = Intent(this, SoloSafeService::class.java).apply { action = ACTION_SOS }
            val sosPending = PendingIntent.getService(this, 1, sosIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(R.drawable.ic_solosafe, "🚨 SOS", sosPending)
        }

        if (addDrivingAction) {
            val drivingIntent = Intent(this, SoloSafeService::class.java).apply {
                action = ACTION_START_PROTECTED
                putExtra(EXTRA_PRESET, "VEHICLE")
            }
            val drivingPending = PendingIntent.getService(this, 2, drivingIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(R.drawable.ic_solosafe, "🚗 Sto guidando", drivingPending)
        }

        return builder.build()
    }

    private fun placeCall(phone: String) {
        try {
            val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(callIntent)
            android.util.Log.d("SoloSafe", "SoloSafeService: placeCall started for $phone")
        } catch (e: Exception) {
            android.util.Log.e("SoloSafe", "SoloSafeService: placeCall failed: ${e.message}")
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SoloSafe::Service")
            @Suppress("WakelockTimeout")
            wakeLock?.acquire()
        }
    }

    override fun onDestroy() {
        wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val NOTIFICATION_SOS = 2
        const val ACTION_START_STANDBY = "com.solosafe.START_STANDBY"
        const val ACTION_START_PROTECTED = "com.solosafe.START_PROTECTED"
        const val ACTION_STOP = "com.solosafe.STOP"
        const val ACTION_SOS = "com.solosafe.SOS"
        const val ACTION_PLACE_CALL = "com.solosafe.PLACE_CALL"
        const val EXTRA_PRESET = "preset"
        const val EXTRA_SESSION_END = "session_end"
        const val EXTRA_PHONE = "phone"

        fun startStandby(context: Context) {
            val intent = Intent(context, SoloSafeService::class.java).apply { action = ACTION_START_STANDBY }
            context.startForegroundService(intent)
        }

        fun startProtected(context: Context, preset: String, sessionEnd: String? = null) {
            val intent = Intent(context, SoloSafeService::class.java).apply {
                action = ACTION_START_PROTECTED
                putExtra(EXTRA_PRESET, preset)
                sessionEnd?.let { putExtra(EXTRA_SESSION_END, it) }
            }
            context.startForegroundService(intent)
        }
    }
}
