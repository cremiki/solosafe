package com.solosafe.app.sensor

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.*

/**
 * Manages alarm sounds and vibration patterns.
 * Pre-alarm: repeating tone every 2s
 * Full alarm: emergency tone + SOS vibration (3 short, 3 long, 3 short)
 */
class AlarmSoundManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var soundJob: Job? = null
    private var toneGenerator: ToneGenerator? = null

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    /** Pre-alarm: warning tone every 2s — stops on cancel */
    fun startPreAlarm() {
        stop()
        soundJob = scope.launch {
            Log.d("SoloSafe", "Pre-alarm sound started")
            while (isActive) {
                try {
                    toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                    toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 500)
                } catch (_: Exception) {}
                // Short vibration burst
                vibrate(longArrayOf(0, 200, 100, 200))
                delay(2000)
            }
        }
    }

    /** Full alarm: emergency sound + SOS vibration pattern */
    fun startFullAlarm() {
        stop()
        soundJob = scope.launch {
            Log.d("SoloSafe", "Full alarm sound started")
            while (isActive) {
                try {
                    toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                    toneGenerator?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1000)
                } catch (_: Exception) {}
                // SOS pattern: 3 short, 3 long, 3 short
                vibrate(longArrayOf(
                    0,
                    200, 100, 200, 100, 200,  // 3 short
                    300,
                    500, 200, 500, 200, 500,  // 3 long
                    300,
                    200, 100, 200, 100, 200,  // 3 short
                ))
                delay(3000)
            }
        }
    }

    fun stop() {
        soundJob?.cancel()
        soundJob = null
        try {
            toneGenerator?.release()
            toneGenerator = null
        } catch (_: Exception) {}
        Log.d("SoloSafe", "Alarm sound stopped")
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    private fun vibrate(pattern: LongArray) {
        try {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {}
    }
}
