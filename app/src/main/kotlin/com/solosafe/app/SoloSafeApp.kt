package com.solosafe.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SoloSafeApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannels()
        } catch (e: Exception) {
            Log.e("SoloSafeApp", "Failed to create notification channels", e)
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return

        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            "Servizio SoloSafe",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Stato del servizio SoloSafe"
            setShowBadge(false)
        }

        val alarmChannel = NotificationChannel(
            CHANNEL_ALARM,
            "Allarmi SoloSafe",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Allarmi e SOS"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
        }

        manager.createNotificationChannel(serviceChannel)
        manager.createNotificationChannel(alarmChannel)
    }

    companion object {
        const val CHANNEL_SERVICE = "solosafe_service"
        const val CHANNEL_ALARM = "solosafe_alarm"
        const val PREFS_NAME = "solosafe_prefs"
        const val KEY_OPERATOR_ID = "operator_id"
        const val KEY_CONFIGURED = "is_configured"
    }
}
