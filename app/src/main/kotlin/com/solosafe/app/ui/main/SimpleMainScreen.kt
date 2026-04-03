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
import com.solosafe.app.sensor.AlarmSoundManager
import com.solosafe.app.sensor.FallDetector
import com.solosafe.app.sensor.ImmobilityDetector
import com.solosafe.app.sensor.MaloreDetector
import com.solosafe.app.sensor.PresetThresholds
import com.solosafe.app.service.SoloSafeService
import com.solosafe.app.service.HeartbeatManager
import com.solosafe.app.service.SessionExpiryManager
import com.solosafe.app.service.SmsAlertManager
import com.solosafe.app.service.OfflineSyncManager
import androidx.room.Room
import com.solosafe.app.data.local.AppDatabase
import com.solosafe.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

private enum class ScreenState { STANDBY, PROTECTED, SOS_SENT }
private enum class PreAlarmType { NONE, MAN_DOWN, MALORE, IMMOBILITY }

@Composable
fun SimpleMainScreen(onOpenSettings: () -> Unit = {}) {
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
    var currentSessionId by remember { mutableStateOf<String?>(null) }
    var showExpiryDialog by remember { mutableStateOf(false) }
    var expiryMessage by remember { mutableStateOf<String?>(null) }
    var slotError by remember { mutableStateOf(false) }
    var showSessionDialog by remember { mutableStateOf(false) }
    var sosMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var preAlarmType by remember { mutableStateOf(PreAlarmType.NONE) }
    var preAlarmCountdown by remember { mutableIntStateOf(30) }
    var showAuthNumbers by remember { mutableStateOf(false) }
    var authNumbers by remember { mutableStateOf(listOf<String>()) }
    var newNumber by remember { mutableStateOf("") }

    val supabase = remember { SupabaseClient() }
    val roomDb = remember { Room.databaseBuilder(context, AppDatabase::class.java, "solosafe.db").fallbackToDestructiveMigration().build() }
    val offlineSync = remember { OfflineSyncManager(context, supabase, roomDb) }
    val heartbeat = remember { HeartbeatManager(context, supabase) }
    val alarmSound = remember { AlarmSoundManager(context) }
    val thresholds = remember { PresetThresholds.forPreset(defaultPreset) }
    val sessionExpiry = remember { SessionExpiryManager() }
    val fallDetector = remember { FallDetector(context, thresholds) }
    val immobilityDetector = remember { ImmobilityDetector(context, thresholds) }
    val maloreDetector = remember { MaloreDetector(context) }

    // Helper: send alarm safely without crashing UI
    fun fireAlarm(type: String, message: String) {
        preAlarmType = PreAlarmType.NONE
        alarmSound.startFullAlarm()
        appState = ScreenState.SOS_SENT
        sosMessage = message
        scope.launch {
            try {
                val gps = withContext(Dispatchers.IO) { heartbeat.getLastLocation() }
                withContext(Dispatchers.IO) {
                    supabase.sendAlarm(operatorId, companyId, type, gps?.first, gps?.second)
                    supabase.sendHeartbeat(operatorId, "alarm", 0, null, gps?.first, gps?.second)
                    SmsAlertManager.sendAlertSms(context, type, operatorName, gps?.first, gps?.second)
                }
                Log.d("SoloSafe", "Alarm sent: $type")
            } catch (e: Exception) {
                Log.e("SoloSafe", "Alarm send failed: ${e.message}")
            }
        }
    }

    // Collect fall detector events
    LaunchedEffect(Unit) {
        fallDetector.events.collect { event ->
            try {
                when (event) {
                    is FallDetector.Event.PreAlarm -> {
                        if (preAlarmType == PreAlarmType.NONE) {
                            preAlarmType = PreAlarmType.MAN_DOWN
                            preAlarmCountdown = 30
                            alarmSound.startPreAlarm()
                        }
                    }
                    is FallDetector.Event.Alarm -> {
                        preAlarmType = PreAlarmType.NONE
                        fireAlarm("MAN_DOWN", "Allarme Man Down inviato!")
                    }
                    is FallDetector.Event.Cancelled -> {
                        preAlarmType = PreAlarmType.NONE
                        alarmSound.stop()
                    }
                }
            } catch (e: Exception) {
                Log.e("SoloSafe", "Fall event handler error: ${e.message}")
            }
        }
    }

    // Collect immobility detector events
    LaunchedEffect(Unit) {
        immobilityDetector.events.collect { event ->
            try {
                when (event) {
                    is ImmobilityDetector.Event.PreAlarm -> {
                        if (preAlarmType == PreAlarmType.NONE) {
                            preAlarmType = PreAlarmType.IMMOBILITY
                            preAlarmCountdown = 30
                            alarmSound.startPreAlarm()
                        }
                    }
                    is ImmobilityDetector.Event.Alarm -> {
                        preAlarmType = PreAlarmType.NONE
                        fireAlarm("IMMOBILITY", "Allarme immobilità inviato!")
                    }
                    is ImmobilityDetector.Event.Cancelled -> {
                        preAlarmType = PreAlarmType.NONE
                        alarmSound.stop()
                    }
                }
            } catch (e: Exception) {
                Log.e("SoloSafe", "Immobility event error: ${e.message}")
            }
        }
    }

    // Collect malore detector events
    LaunchedEffect(Unit) {
        maloreDetector.events.collect { event ->
            try {
                when (event) {
                    is MaloreDetector.Event.Calibrating -> {}
                    is MaloreDetector.Event.Ready -> {}
                    is MaloreDetector.Event.PreAlarm -> {
                        if (preAlarmType == PreAlarmType.NONE) {
                            preAlarmType = PreAlarmType.MALORE
                            preAlarmCountdown = 30
                            alarmSound.startPreAlarm()
                        }
                    }
                    is MaloreDetector.Event.Alarm -> {
                        preAlarmType = PreAlarmType.NONE
                        fireAlarm("MALORE", "Allarme Malore inviato!")
                    }
                    is MaloreDetector.Event.Cancelled -> {
                        preAlarmType = PreAlarmType.NONE
                        alarmSound.stop()
                    }
                }
            } catch (e: Exception) {
                Log.e("SoloSafe", "Malore event error: ${e.message}")
            }
        }
    }

    // Session expiry events
    LaunchedEffect(Unit) {
        sessionExpiry.events.collect { event ->
            when (event) {
                is SessionExpiryManager.Event.Warning15 -> {
                    expiryMessage = "Sessione in scadenza tra 15 minuti"
                }
                is SessionExpiryManager.Event.Expired -> {
                    showExpiryDialog = true
                    expiryMessage = null
                }
                is SessionExpiryManager.Event.PreAlarm -> {
                    showExpiryDialog = false
                    preAlarmType = PreAlarmType.IMMOBILITY // reuse pre-alarm UI
                    preAlarmCountdown = 30
                    alarmSound.startPreAlarm()
                }
                is SessionExpiryManager.Event.Alarm -> {
                    preAlarmType = PreAlarmType.NONE
                    fireAlarm("SESSION_EXPIRED", "Sessione scaduta — allarme inviato!")
                }
                is SessionExpiryManager.Event.Extended -> {
                    showExpiryDialog = false
                    expiryMessage = "Sessione estesa"
                }
            }
        }
    }

    // Pre-alarm countdown timer — cancels automatically when preAlarmType changes
    LaunchedEffect(preAlarmType) {
        if (preAlarmType != PreAlarmType.NONE) {
            try {
                preAlarmCountdown = 30
                while (preAlarmCountdown > 0) {
                    delay(1000)
                    preAlarmCountdown--
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Normal cancellation when user presses "STO BENE"
            } catch (e: Exception) {
                Log.e("SoloSafe", "Countdown error: ${e.message}")
            }
        }
    }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            fallDetector.destroy()
            immobilityDetector.destroy()
            maloreDetector.destroy()
            sessionExpiry.destroy()
            offlineSync.destroy()
            heartbeat.destroy()
            alarmSound.destroy()
        }
    }

    // Heartbeat + offline sync
    LaunchedEffect(Unit) {
        heartbeat.startStandby()
        offlineSync.startPeriodicSync()
        HeartbeatManager.scheduleWorkManagerFallback(context, "standby")

        // Load authorized numbers
        val saved = prefs.getString("authorized_numbers", null)
        if (saved != null) {
            authNumbers = saved.split(",").filter { it.isNotBlank() }
        }
        // Fetch from Supabase if empty
        if (authNumbers.isEmpty() && operatorId.isNotBlank()) {
            try {
                val phones = withContext(Dispatchers.IO) { supabase.getAuthorizedPhones(operatorId) }
                if (phones.isNotEmpty()) {
                    authNumbers = phones
                    prefs.edit().putString("authorized_numbers", phones.joinToString(",")).commit()
                    Log.d("SoloSafe", "Loaded ${phones.size} authorized numbers from Supabase")
                }
            } catch (_: Exception) {}
        }
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
                    SessionOption("30 minuti (test)", "turno", 0) { type, _ ->
                        sessionType = type; sessionDurationHours = 0; showSessionDialog = false
                        // 30 min = pass as minutes via a special value
                        startSession(scope, context, supabase, heartbeat, operatorId, companyId, defaultPreset, type, null, onSuccess = { sid ->
                            appState = ScreenState.PROTECTED; sessionStart = System.currentTimeMillis(); currentSessionId = sid
                            prefs.edit().putString("current_state", "protected").commit()
                            try { SoloSafeService.startProtected(context, defaultPreset) } catch (_: Exception) {}
                            heartbeat.startProtected()
                            HeartbeatManager.scheduleWorkManagerFallback(context, "protected")
                            fallDetector.start(); immobilityDetector.start(); maloreDetector.start()
                            sessionExpiry.start(System.currentTimeMillis() + 30 * 60_000L)
                        }, onSlotsFull = { slotError = true })
                    }
                    SessionOption("1 ora", "turno", 1) { type, hours ->
                        sessionType = type; sessionDurationHours = hours; showSessionDialog = false
                        startSession(scope, context, supabase, heartbeat, operatorId, companyId, defaultPreset, type, hours, onSuccess = { sid ->
                            appState = ScreenState.PROTECTED; sessionStart = System.currentTimeMillis(); currentSessionId = sid
                            prefs.edit().putString("current_state", "protected").commit()
                            try { SoloSafeService.startProtected(context, defaultPreset) } catch (_: Exception) {}
                            heartbeat.startProtected()
                            HeartbeatManager.scheduleWorkManagerFallback(context, "protected")
                            fallDetector.start(); immobilityDetector.start(); maloreDetector.start()
                            if (hours > 0) sessionExpiry.start(System.currentTimeMillis() + hours * 3600_000L)
                        }, onSlotsFull = { slotError = true })
                    }
                    SessionOption("Turno 4 ore", "turno", 4) { type, hours ->
                        sessionType = type; sessionDurationHours = hours; showSessionDialog = false
                        startSession(scope, context, supabase, heartbeat, operatorId, companyId, defaultPreset, type, hours, onSuccess = { sid ->
                            appState = ScreenState.PROTECTED; sessionStart = System.currentTimeMillis(); currentSessionId = sid
                            prefs.edit().putString("current_state", "protected").commit()
                            try { SoloSafeService.startProtected(context, defaultPreset) } catch (_: Exception) {}
                            heartbeat.startProtected()
                            HeartbeatManager.scheduleWorkManagerFallback(context, "protected")
                            fallDetector.start(); immobilityDetector.start(); maloreDetector.start()
                            if (hours > 0) sessionExpiry.start(System.currentTimeMillis() + hours * 3600_000L)
                        }, onSlotsFull = { slotError = true })
                    }
                    SessionOption("Turno 4 ore", "turno", 4) { type, hours ->
                        sessionType = type; sessionDurationHours = hours; showSessionDialog = false
                        startSession(scope, context, supabase, heartbeat, operatorId, companyId, defaultPreset, type, hours, onSuccess = { sid ->
                            appState = ScreenState.PROTECTED; sessionStart = System.currentTimeMillis(); currentSessionId = sid
                            prefs.edit().putString("current_state", "protected").commit()
                            try { SoloSafeService.startProtected(context, defaultPreset) } catch (_: Exception) {}
                            heartbeat.startProtected()
                            HeartbeatManager.scheduleWorkManagerFallback(context, "protected")
                            fallDetector.start(); immobilityDetector.start(); maloreDetector.start()
                            if (hours > 0) sessionExpiry.start(System.currentTimeMillis() + hours * 3600_000L)
                        }, onSlotsFull = { slotError = true })
                    }
                    SessionOption("Continua (H24)", "continua", 0) { type, _ ->
                        sessionType = type; sessionDurationHours = 0; showSessionDialog = false
                        startSession(scope, context, supabase, heartbeat, operatorId, companyId, defaultPreset, type, null, onSuccess = { sid ->
                            appState = ScreenState.PROTECTED; sessionStart = System.currentTimeMillis(); currentSessionId = sid
                            prefs.edit().putString("current_state", "protected").commit()
                            try { SoloSafeService.startProtected(context, defaultPreset) } catch (_: Exception) {}
                            heartbeat.startProtected()
                            HeartbeatManager.scheduleWorkManagerFallback(context, "protected")
                            fallDetector.start(); immobilityDetector.start(); maloreDetector.start()
                            // Continua: no expiry
                        }, onSlotsFull = { slotError = true })
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

    // Slot full error
    if (slotError) {
        AlertDialog(
            onDismissRequest = { slotError = false },
            containerColor = Surface,
            title = { Text("Slot esauriti", color = Alarm, fontWeight = FontWeight.Bold) },
            text = { Text("Tutti gli slot concorrenti sono occupati. Contatta il responsabile.", color = TextPrimary, fontSize = 14.sp) },
            confirmButton = { TextButton(onClick = { slotError = false }) { Text("OK", color = TextSecondary) } },
        )
    }

    // Session expiry dialog — "Vuoi estendere?"
    if (showExpiryDialog) {
        AlertDialog(
            onDismissRequest = {},
            containerColor = Surface,
            title = { Text("Sessione scaduta", color = Warning, fontWeight = FontWeight.Bold) },
            text = { Text("Il turno è terminato. Vuoi estendere la sessione?", color = TextPrimary, fontSize = 14.sp) },
            confirmButton = {
                Button(
                    onClick = { sessionExpiry.extend(1); showExpiryDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Protected),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("+1 ora", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { sessionExpiry.extend(2) }) { Text("+2 ore", color = TextSecondary) }
                    TextButton(onClick = {
                        showExpiryDialog = false
                        // End session
                        appState = ScreenState.STANDBY; sessionStart = null
                        prefs.edit().putString("current_state", "standby").commit()
                        scope.launch { try { currentSessionId?.let { supabase.endSession(it) } } catch (_: Exception) {} }
                        fallDetector.stop(); immobilityDetector.stop(); maloreDetector.stop(); sessionExpiry.stop()
                        heartbeat.startStandby()
                        try { SoloSafeService.startStandby(context) } catch (_: Exception) {}
                    }) { Text("Termina", color = Alarm) }
                }
            },
        )
    }

    // Pre-alarm FULLSCREEN
    if (preAlarmType != PreAlarmType.NONE && preAlarmCountdown >= 0) {
        // Flashing red background
        val flashAlpha by animateColorAsState(
            targetValue = if (preAlarmCountdown % 2 == 0) Color(0xFFCC0000) else Color(0xFF880000),
            label = "flash"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(flashAlpha)
                .systemBarsPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize().padding(32.dp),
            ) {
                Spacer(modifier = Modifier.weight(0.3f))

                // Alarm type label
                Text(
                    when (preAlarmType) {
                        PreAlarmType.MAN_DOWN -> "CADUTA"
                        PreAlarmType.MALORE -> "MALORE"
                        PreAlarmType.IMMOBILITY -> "IMMOBILITÀ"
                        else -> "ALLARME"
                    },
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 4.sp,
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Warning icon
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(100.dp),
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Countdown
                Text(
                    "$preAlarmCountdown",
                    color = Color.White,
                    fontSize = 80.sp,
                    fontWeight = FontWeight.Black,
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Progress arc
                LinearProgressIndicator(
                    progress = { (preAlarmCountdown.coerceIn(0, 30)) / 30f },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = Color.White,
                    trackColor = Color(0x33FFFFFF),
                )

                Spacer(modifier = Modifier.weight(0.3f))

                // BIG green button — takes half the screen
                Button(
                    onClick = {
                        fallDetector.cancelAlarm()
                        immobilityDetector.cancelAlarm()
                        maloreDetector.cancelAlarm()
                        preAlarmType = PreAlarmType.NONE
                        alarmSound.stop()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Protected),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth().weight(0.4f),
                ) {
                    Text(
                        "STO BENE",
                        fontWeight = FontWeight.Black,
                        fontSize = 36.sp,
                        color = Color.White,
                    )
                }
            }
        }
        return // Don't render anything else during pre-alarm
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(operatorName, color = TextSecondary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.size(32.dp),
                        enabled = appState == ScreenState.STANDBY,
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Impostazioni",
                            tint = if (appState == ScreenState.STANDBY) TextSecondary else Color(0xFF2A2D3E),
                            modifier = Modifier.size(20.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            when (appState) {
                ScreenState.STANDBY -> {
                    Icon(Icons.Default.Shield, contentDescription = null, tint = SoloSafeRed, modifier = Modifier.size(80.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("SoloSafe", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("Worker Safety", color = TextSecondary, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(32.dp))

                    Button(
                        onClick = { showSessionDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Protected),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                    ) {
                        Text("ATTIVA PROTEZIONE", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                    }

                    // Authorized numbers moved to Settings
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

                    // Expiry warning
                    expiryMessage?.let { msg ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF3D2A00), modifier = Modifier.fillMaxWidth()) {
                            Text(msg, color = Warning, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(12.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    SwipeToConfirm(text = "Scorri per terminare") {
                        appState = ScreenState.STANDBY
                        sessionStart = null
                        prefs.edit().putString("current_state", "standby").commit()
                        scope.launch {
                            try {
                                if (currentSessionId != null) {
                                    supabase.endSession(currentSessionId!!)
                                } else {
                                    supabase.endAllSessions(operatorId)
                                }
                                supabase.sendHeartbeat(operatorId, "standby", 0, null, null, null)
                                Log.d("SoloSafe", "Session ended + status set to standby")
                            } catch (e: Exception) {
                                Log.e("SoloSafe", "End session error: ${e.message}")
                            }
                        }
                        currentSessionId = null
                        fallDetector.stop(); immobilityDetector.stop(); maloreDetector.stop(); sessionExpiry.stop()
                        heartbeat.startStandby()
                        try { SoloSafeService.startStandby(context) } catch (_: Exception) {}
                        HeartbeatManager.scheduleWorkManagerFallback(context, "standby")
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
                        alarmSound.stop()
                        val newState = if (sessionStart != null) "protected" else "standby"
                        appState = if (sessionStart != null) ScreenState.PROTECTED else ScreenState.STANDBY
                        scope.launch {
                            try { supabase.sendHeartbeat(operatorId, newState, 0, null, null, null) } catch (_: Exception) {}
                        }
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
                    alarmSound.startFullAlarm()
                    scope.launch {
                        try {
                            val gps = withContext(Dispatchers.IO) { heartbeat.getLastLocation() }
                            withContext(Dispatchers.IO) {
                                supabase.sendAlarm(operatorId, companyId, "SOS", gps?.first, gps?.second)
                                supabase.sendHeartbeat(operatorId, "alarm", 0, null, gps?.first, gps?.second)
                                SmsAlertManager.sendAlertSms(context, "SOS", operatorName, gps?.first, gps?.second)
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
    onSuccess: (sessionId: String?) -> Unit,
    onSlotsFull: () -> Unit = {},
) {
    scope.launch {
        var sessionId: String? = null
        try {
            // Slot check first
            val slotsOk = withContext(Dispatchers.IO) { supabase.checkSlotAvailable(companyId) }
            if (!slotsOk) {
                Log.w("SoloSafe", "No slots available!")
                onSlotsFull()
                return@launch
            }

            withContext(Dispatchers.IO) {
                val plannedEnd = durationHours?.let {
                    java.time.Instant.now().plusSeconds(it.toLong() * 3600).toString()
                }
                sessionId = supabase.startSession(operatorId, companyId, sessionType, preset, plannedEnd)
                val gps = heartbeat.getLastLocation()
                val battery = (context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager)
                    .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                supabase.sendHeartbeat(operatorId, "protected", battery, null, gps?.first, gps?.second)
            }
            Log.d("SoloSafe", "Session started: id=$sessionId type=$sessionType")
            onSuccess(sessionId)
        } catch (e: Exception) {
            Log.e("SoloSafe", "Start session failed: ${e.message}")
            onSuccess(null)
        }
    }
}
