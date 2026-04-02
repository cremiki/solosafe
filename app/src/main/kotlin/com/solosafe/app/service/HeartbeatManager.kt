package com.solosafe.app.service

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import androidx.work.*
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import com.solosafe.app.SoloSafeApp
import com.solosafe.app.data.remote.SupabaseClient
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/**
 * Heartbeat manager — runs inside the Foreground Service coroutine scope.
 * WorkManager minimum interval is 15min, but we need 5min for PROTECTED.
 * Solution: coroutine loop with delay.
 */
class HeartbeatManager(
    private val context: Context,
    private val supabaseClient: SupabaseClient,
) {
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun startStandby() {
        stop()
        job = scope.launch {
            Log.d("SoloSafe", "Heartbeat STANDBY: every 30min")
            while (isActive) {
                sendHeartbeat("standby")
                delay(30 * 60 * 1000L) // 30 minutes
            }
        }
    }

    fun startProtected() {
        stop()
        job = scope.launch {
            Log.d("SoloSafe", "Heartbeat PROTECTED: every 5min")
            sendHeartbeat("protected")
            while (isActive) {
                delay(5 * 60 * 1000L) // 5 minutes
                sendHeartbeat("protected")
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    /** Send one heartbeat now (can be called from anywhere) */
    suspend fun sendOnce(state: String) {
        sendHeartbeat(state)
    }

    /** Get current GPS location (for use by alarms too) */
    @Suppress("MissingPermission")
    fun getLastLocation(): Pair<Double, Double>? {
        return try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val task = client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            val location = Tasks.await(task, 10, TimeUnit.SECONDS)
            location?.let {
                Log.d("SoloSafe", "GPS: ${it.latitude}, ${it.longitude} (accuracy: ${it.accuracy}m)")
                Pair(it.latitude, it.longitude)
            }
        } catch (e: Exception) {
            Log.w("SoloSafe", "GPS unavailable: ${e.message}")
            null
        }
    }

    private suspend fun sendHeartbeat(state: String) {
        val prefs = context.getSharedPreferences(SoloSafeApp.PREFS_NAME, Context.MODE_PRIVATE)
        val operatorId = prefs.getString(SoloSafeApp.KEY_OPERATOR_ID, null) ?: return

        val battery = getBatteryLevel()
        val location = withContext(Dispatchers.IO) { getLastLocation() }

        try {
            supabaseClient.sendHeartbeat(
                operatorId = operatorId,
                state = state,
                batteryPhone = battery,
                batteryTag = null,
                lat = location?.first,
                lng = location?.second,
            )
            Log.d("SoloSafe", "Heartbeat OK: state=$state, battery=$battery%, gps=${location != null}")
        } catch (e: Exception) {
            Log.e("SoloSafe", "Heartbeat failed: ${e.message}")
        }
    }

    private fun getBatteryLevel(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    companion object {
        // Keep WorkManager as fallback for when app is killed
        fun scheduleWorkManagerFallback(context: Context, state: String) {
            val interval = if (state == "protected") 15L else 30L
            val request = PeriodicWorkRequestBuilder<HeartbeatFallbackWorker>(interval, TimeUnit.MINUTES)
                .setInputData(workDataOf("state" to state))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("heartbeat_fallback", ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancelWorkManager(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork("heartbeat_fallback")
        }
    }
}

/** Fallback WorkManager worker — runs only if Foreground Service is killed */
class HeartbeatFallbackWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(SoloSafeApp.PREFS_NAME, Context.MODE_PRIVATE)
        val operatorId = prefs.getString(SoloSafeApp.KEY_OPERATOR_ID, null) ?: return Result.success()
        val state = inputData.getString("state") ?: "standby"

        return try {
            val supabase = SupabaseClient()
            val battery = (applicationContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            supabase.sendHeartbeat(operatorId, state, battery, null, null, null)
            Log.d("SoloSafe", "Heartbeat fallback OK: state=$state")
            Result.success()
        } catch (e: Exception) {
            Log.e("SoloSafe", "Heartbeat fallback failed: ${e.message}")
            Result.retry()
        }
    }
}
