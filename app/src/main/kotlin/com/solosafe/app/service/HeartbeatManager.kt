package com.solosafe.app.service

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import com.solosafe.app.SoloSafeApp
import com.solosafe.app.data.remote.SupabaseClient
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class HeartbeatWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val supabaseClient: SupabaseClient,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(SoloSafeApp.PREFS_NAME, Context.MODE_PRIVATE)
        val operatorId = prefs.getString(SoloSafeApp.KEY_OPERATOR_ID, null)
        if (operatorId == null) {
            Log.w("SoloSafe", "HeartbeatWorker: no operator_id, skipping")
            return Result.success()
        }

        val state = inputData.getString("state") ?: "standby"
        val battery = getBatteryLevel()
        val location = getLastLocation()

        return try {
            supabaseClient.sendHeartbeat(
                operatorId = operatorId,
                state = state,
                batteryPhone = battery,
                batteryTag = null,
                lat = location?.first,
                lng = location?.second,
            )
            Log.d("SoloSafe", "Heartbeat sent: state=$state, battery=$battery, gps=${location != null}")
            Result.success()
        } catch (e: Exception) {
            Log.e("SoloSafe", "Heartbeat failed: ${e.message}")
            Result.retry()
        }
    }

    private fun getBatteryLevel(): Int {
        val bm = applicationContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    @Suppress("MissingPermission")
    private fun getLastLocation(): Pair<Double, Double>? {
        return try {
            val client = LocationServices.getFusedLocationProviderClient(applicationContext)
            val task = client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            val location = Tasks.await(task, 10, TimeUnit.SECONDS)
            location?.let { Pair(it.latitude, it.longitude) }
        } catch (e: Exception) {
            Log.w("SoloSafe", "Location unavailable: ${e.message}")
            null
        }
    }

    companion object {
        private const val WORK_STANDBY = "heartbeat_standby"
        private const val WORK_PROTECTED = "heartbeat_protected"

        fun scheduleStandby(context: Context) {
            val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(30, TimeUnit.MINUTES)
                .setInputData(workDataOf("state" to "standby"))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_STANDBY, ExistingPeriodicWorkPolicy.UPDATE, request)
            WorkManager.getInstance(context).cancelUniqueWork(WORK_PROTECTED)
        }

        fun scheduleProtected(context: Context) {
            val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(5, TimeUnit.MINUTES)
                .setInputData(workDataOf("state" to "protected"))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_PROTECTED, ExistingPeriodicWorkPolicy.UPDATE, request)
            WorkManager.getInstance(context).cancelUniqueWork(WORK_STANDBY)
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_STANDBY)
            WorkManager.getInstance(context).cancelUniqueWork(WORK_PROTECTED)
        }
    }
}
