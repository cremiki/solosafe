package com.solosafe.app.ui.main

import android.content.Context
import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.solosafe.app.SoloSafeApp
import com.solosafe.app.data.remote.SupabaseClient
import com.solosafe.app.sensor.FallDetector
import com.solosafe.app.sensor.ImmobilityDetector
import com.solosafe.app.sensor.PresetThresholds
import com.solosafe.app.service.SoloSafeService
import com.solosafe.app.service.HeartbeatManager
import com.solosafe.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

private enum class ScreenState { STANDBY, PROTECTED, SOS_SENT }
private enum class PreAlarmType { NONE, FALL, IMMOBILITY }

@Composable
fun SimpleMainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember {
        context.getSharedPreferences(SoloSafeApp.PREFS_NAME, Context.MODE_PRIVATE)
    }

    val operatorName = prefs.getString("operator_name", "Operatore") ?: "Operatore"
    val operatorId = prefs.getString(SoloSafeApp.KEY_OPERATOR_ID, "") ?: ""
    val companyId = prefs.getString("company_id", "") ?: ""
    val defaultPreset = prefs.getString("default_preset", "WAREHOUSE") ?: "WAREHOUSE"

    var appState by remember { mutableStateOf(ScreenState.STANDBY) }
    var sessionStart by remember { mutableStateOf<Long?>(null) }
    var sessionDurationHours by remember { mutableIntStateOf(0) }
    var sessionType by remember { mutableStateOf("") }
    var showSessionDialog by remember { mutableStateOf(false) }
    var sosMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var preAlarmType by remember { mutableStateOf(PreAlarmType.NONE) }
    var preAlarmCountdown by remember { mutableIntStateOf(30) }

    val supabase = remember { SupabaseClient() }
    val heartbeat = remember { HeartbeatManager(context, supabase) }
    val thresholds = remember { PresetThresholds.forPreset(defaultPreset) }
    val fallDetector = remember { FallDetector(context, thresholds) }
    val immobilityDetector = remember { ImmobilityDetector(context, thresholds) }

    // Collect fall detector events
    LaunchedEffect(Unit) {
        fallDetector.events.collect { event ->
            when (event) {
                is FallDetector.Event.PreAlarm -> {
                    preAlarmType = PreAlarmType.FALL
                    preAlarmCountdown = 30
                }
                is FallDetector.Event.Alarm -> {
                    preAlarmType = PreAlarmType.NONE
                    // Send real alarm
                    try {
                        val gps = withContext(Dispatchers.IO) { heartbeat.getLastLocation() }
                        withContext(Dispatchers.IO) {
                            supabase.sendAlarm(operatorId, companyId, "FALL", gps?.first, gps?.second)
                        }
                        appState = ScreenState.SOS_SENT
                        sosMessage = "Allarme caduta inviato!"
                    } catch (_: Exception) {}
                }
                is FallDetector.Event.Cancelled -> {
                    preAlarmType = PreAlarmType.NONE
                }
            }
        }
    }

    // Collect immobility detector events
    LaunchedEffect(Unit) {
        immobilityDetector.events.collect { event ->
            when (event) {
                is ImmobilityDetector.Event.PreAlarm -> {
                    preAlarmType = PreAlarmType.IMMOBILITY
                    preAlarmCountdown = 30
                }
                is ImmobilityDetector.Event.Alarm -> {
                    preAlarmType = PreAlarmType.NONE
                    try {
                        val gps = withContext(Dispatchers.IO) { heartbeat.getLastLocation() }
                        withContext(Dispatchers.IO) {
                            supabase.sendAlarm(operatorId, companyId, "IMMOBILITY", gps?.first, gps?.second)
                        }
                        appState = ScreenState.SOS_SENT
                        sosMessage = "Allarme immobilità inviato!"
                    } catch (_: Exception) {}
                }
                is ImmobilityDetector.Event.Cancelled -> {
                    preAlarmType = PreAlarmType.NONE
                }
            }
        }
    }

    // Pre-alarm countdown timer
    LaunchedEffect(preAlarmType) {
        if (preAlarmType != PreAlarmType.NONE) {
            preAlarmCountdown = 30
            while (preAlarmCountdown > 0) {
                delay(1000)
                preAlarmCountdown--
            }
        }
    }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            fallDetector.destroy()
            immobilityDetector.destroy()
            heartbeat.destroy()
        }
    }

    // Heartbeat in standby mode (low frequency, no foreground service)
    LaunchedEffect(Unit) {
        heartbeat.startStandby()
        HeartbeatManager.scheduleWorkManagerFallback(context, "standby")
    }

    val bgColor by animateColorAsState(
        when (appState) {
            ScreenState.STANDBY -> Background
            ScreenState.PROTECTED -> Background
            ScreenState.SOS_SENT -> Color(0xFF1A0000)
        }, label = "bg"
    )

    // Session dialog
    if (showSessionDialog) {
        AlertDialog(
            onDismissRequest = { showSessionDialog = false },
            containerColor = Surface,
            title = { Text("Avvia protezione", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SessionOption("Turno 8 ore", "turno", 8) { type, hours ->
                        sessionType = type; sessionDurationHours = hours; showSessionDialog = false
                        startSession(scope, context, supabase, heartbeat, operatorId, companyId, defaultPreset, type, hours) {
                            appState = ScreenState.PROTECTED; sessionStart = System.currentTimeMillis()
                            try { SoloSafeService.startProtected(context, defaultPreset) } catch (_: Exception) {}
                            heartbeat.startProtected()
                            HeartbeatManager.scheduleWorkManagerFallback(context, "protected")
                            fallDetector.start(); immobilityDetector.start()
                        }
                    }
                    SessionOption("Turno 4 ore", "turno", 4) { type, hours ->
                        sessionType = type; sessionDurationHours = hours; showSessionDialog = false
                        startSession(scope, context, supabase, heartbeat, operatorId, companyId, defaultPreset, type, hours) {
                            appState = ScreenState.PROTECTED; sessionStart = System.currentTimeMillis()
                            try { SoloSafeService.startProtected(context, defaultPreset) } catch (_: Exception) {}
                            heartbeat.startProtected()
                            HeartbeatManager.scheduleWorkManagerFallback(context, "protected")
                            fallDetector.start(); immobilityDetector.start()
                        }
                    }
                    SessionOption("Continua (H24)", "continua", 0) { type, _ ->
                        sessionType = type; sessionDurationHours = 0; showSessionDialog = false
                        startSession(scope, context, supabase, operatorId, companyId, defaultPreset, type, null) {
                            appState = ScreenState.PROTECTED; sessionStart = System.currentTimeMillis()
                            try { SoloSafeService.startProtected(context, defaultPreset) } catch (_: Exception) {}
                            heartbeat.startProtected()
                            HeartbeatManager.scheduleWorkManagerFallback(context, "protected")
                            fallDetector.start(); immobilityDetector.start()
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSessionDialog = false }) {
                    Text("Annulla", color = TextSecondary)
                }
            },
        )
    }

    // Pre-alarm dialog — "Stai bene?"
    if (preAlarmType != PreAlarmType.NONE) {
        AlertDialog(
            onDismissRequest = { /* can't dismiss by tapping outside */ },
            containerColor = Color(0xFF1A0000),
            title = {
                Text(
                    if (preAlarmType == PreAlarmType.FALL) "Caduta rilevata!" else "Sei immobile da troppo tempo!",
                    color = Alarm,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("Stai bene?", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Allarme automatico tra ${preAlarmCountdown}s",
                        color = Alarm,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { preAlarmCountdown / 30f },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = Alarm,
                        trackColor = Color(0xFF2A0000),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        fallDetector.cancelAlarm()
                        immobilityDetector.cancelAlarm()
                        preAlarmType = PreAlarmType.NONE
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Protected),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Text("SÌ, STO BENE", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                }
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .systemBarsPadding(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(12.dp).background(
                        when (appState) {
                            ScreenState.STANDBY -> Standby
                            ScreenState.PROTECTED -> Protected
                            ScreenState.SOS_SENT -> Alarm
                        }, CircleShape
                    ))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        when (appState) {
                            ScreenState.STANDBY -> "STANDBY"
                            ScreenState.PROTECTED -> "PROTETTO"
                            ScreenState.SOS_SENT -> "SOS INVIATO"
                        },
                        color = when (appState) {
                            ScreenState.STANDBY -> Standby
                            ScreenState.PROTECTED -> Protected
                            ScreenState.SOS_SENT -> Alarm
                        },
                        fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    )
                }
                Text(operatorName, color = TextSecondary, fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.weight(1f))

            when (appState) {
                ScreenState.STANDBY -> {
                    Icon(Icons.Default.Shield, contentDescription = null, tint = SoloSafeRed, modifier = Modifier.size(80.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("SoloSafe", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("Worker Safety", color = TextSecondary, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(48.dp))

                    Button(
                        onClick = { showSessionDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Protected),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                    ) {
                        Text("ATTIVA PROTEZIONE", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                    }
                }

                ScreenState.PROTECTED -> {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF1A3D2A),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text("🟢 PROTETTO", color = Protected, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Operatore: $operatorName", color = TextPrimary, fontSize = 15.sp)
                            Text("Preset: $defaultPreset", color = TextSecondary, fontSize = 13.sp)
                            Text("Sessione: ${sessionType.replaceFirstChar { it.uppercase() }}", color = TextSecondary, fontSize = 13.sp)
                            sessionStart?.let { start ->
                                val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
                                Text("Inizio: ${sdf.format(Date(start))}", color = TextSecondary, fontSize = 13.sp)
                                if (sessionDurationHours > 0) {
                                    val endTime = start + sessionDurationHours * 3600_000L
                                    Text("Fine prevista: ${sdf.format(Date(endTime))}", color = TextSecondary, fontSize = 13.sp)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    OutlinedButton(
                        onClick = {
                            appState = ScreenState.STANDBY
                            sessionStart = null
                            scope.launch {
                                try {
                                    supabase.sendHeartbeat(operatorId, "standby", 100, null, null, null)
                                    Log.d("SoloSafe", "Status set to standby")
                                } catch (e: Exception) {
                                    Log.e("SoloSafe", "End session error: ${e.message}")
                                }
                            }
                            fallDetector.stop(); immobilityDetector.stop()
                            heartbeat.startStandby()
                            try { SoloSafeService.startStandby(context) } catch (_: Exception) {}
                            HeartbeatManager.scheduleWorkManagerFallback(context, "standby")
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    ) {
                        Text("Termina protezione", fontWeight = FontWeight.Medium)
                    }
                }

                ScreenState.SOS_SENT -> {
                    Text("🚨", fontSize = 64.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("SOS INVIATO", color = Alarm, fontWeight = FontWeight.Bold, fontSize = 28.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Allarme inviato ai contatti", color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(24.dp))
                    TextButton(onClick = {
                        appState = if (sessionStart != null) ScreenState.PROTECTED else ScreenState.STANDBY
                    }) {
                        Text("Torna alla schermata", color = TextSecondary)
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // SOS feedback
            sosMessage?.let { msg ->
                Text(msg, color = Protected, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(8.dp))
            }

            // SOS button — always visible
            Button(
                onClick = {
                    if (isLoading) return@Button
                    isLoading = true
                    sosMessage = null
                    scope.launch {
                        try {
                            val gps = withContext(Dispatchers.IO) { heartbeat.getLastLocation() }
                            withContext(Dispatchers.IO) {
                                supabase.sendAlarm(operatorId, companyId, "SOS", gps?.first, gps?.second)
                            }
                            appState = ScreenState.SOS_SENT
                            sosMessage = "✓ Allarme inviato!"
                            Log.d("SoloSafe", "SOS alarm sent successfully")
                        } catch (e: Exception) {
                            sosMessage = "Errore: ${e.message}"
                            Log.e("SoloSafe", "SOS failed: ${e.message}")
                        } finally {
                            isLoading = false
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Alarm),
                shape = CircleShape,
                modifier = Modifier.size(120.dp),
                contentPadding = PaddingValues(0.dp),
                enabled = !isLoading,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                } else {
                    Text("SOS", fontWeight = FontWeight.Black, fontSize = 32.sp, color = Color.White)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Tieni premuto per SOS", color = TextSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SessionOption(
    label: String,
    type: String,
    hours: Int,
    onClick: (String, Int) -> Unit,
) {
    OutlinedButton(
        onClick = { onClick(type, hours) },
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

private fun startSession(
    scope: kotlinx.coroutines.CoroutineScope,
    context: Context,
    supabase: SupabaseClient,
    heartbeat: HeartbeatManager,
    operatorId: String,
    companyId: String,
    preset: String,
    sessionType: String,
    durationHours: Int?,
    onSuccess: () -> Unit,
) {
    scope.launch {
        try {
            withContext(Dispatchers.IO) {
                val plannedEnd = durationHours?.let {
                    java.time.Instant.now().plusSeconds(it.toLong() * 3600).toString()
                }

                // Insert work_session in Supabase
                val sessionId = supabase.startSession(operatorId, companyId, sessionType, preset, plannedEnd)
                Log.d("SoloSafe", "Work session created: id=$sessionId")

                // Get real GPS + battery and update operator_status
                val gps = heartbeat.getLastLocation()
                val battery = (context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager)
                    .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                supabase.sendHeartbeat(operatorId, "protected", battery, null, gps?.first, gps?.second)
            }
            Log.d("SoloSafe", "Session started: type=$sessionType, duration=$durationHours")
            onSuccess()
        } catch (e: Exception) {
            Log.e("SoloSafe", "Start session failed: ${e.message}")
            onSuccess()
        }
    }
}
