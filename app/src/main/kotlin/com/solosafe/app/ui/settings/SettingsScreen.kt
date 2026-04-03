package com.solosafe.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.solosafe.app.SoloSafeApp
import com.solosafe.app.MainActivity
import com.solosafe.app.data.remote.SupabaseClient
import com.solosafe.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(SoloSafeApp.PREFS_NAME, Context.MODE_PRIVATE) }
    val supabase = remember { SupabaseClient() }

    // Alarm thresholds
    var manDownEnabled by remember { mutableStateOf(prefs.getBoolean("malore_enabled", true)) }
    var manDownAngle by remember { mutableFloatStateOf(prefs.getFloat("malore_angle", 45f)) }
    var fallEnabled by remember { mutableStateOf(prefs.getBoolean("fall_enabled", true)) }
    var fallThresholdG by remember { mutableFloatStateOf(prefs.getFloat("fall_threshold_g", 2.5f)) }
    var immobilityEnabled by remember { mutableStateOf(prefs.getBoolean("immobility_enabled", true)) }
    var immobilitySeconds by remember { mutableFloatStateOf(prefs.getFloat("immobility_seconds", 90f)) }

    // Authorized numbers
    var authNumbers by remember {
        mutableStateOf(prefs.getString("authorized_numbers", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList())
    }
    var newNumber by remember { mutableStateOf("") }
    var loadingNumbers by remember { mutableStateOf(false) }

    // Info
    val operatorName = prefs.getString("operator_name", "—") ?: "—"
    val preset = prefs.getString("default_preset", "—") ?: "—"
    val operatorId = prefs.getString(SoloSafeApp.KEY_OPERATOR_ID, "") ?: ""
    val companyId = prefs.getString("company_id", "") ?: ""

    fun logChange(param: String, oldVal: String, newVal: String) {
        if (oldVal != newVal) {
            scope.launch {
                try { supabase.logConfigChange(operatorId, companyId, param, oldVal, newVal) } catch (_: Exception) {}
            }
        }
    }

    fun savePrefs() {
        prefs.edit()
            .putBoolean("malore_enabled", manDownEnabled)
            .putFloat("malore_angle", manDownAngle)
            .putBoolean("fall_enabled", fallEnabled)
            .putFloat("fall_threshold_g", fallThresholdG)
            .putBoolean("immobility_enabled", immobilityEnabled)
            .putFloat("immobility_seconds", immobilitySeconds)
            .commit()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .systemBarsPadding()
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { savePrefs(); onBack() }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Indietro", tint = TextSecondary)
            }
            Text("Impostazioni", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // === SECTION 1: ALARM THRESHOLDS ===
            SectionHeader("Soglie Allarmi")

            // Malore
            SettingsCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Malore / Man Down", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Cambio orientamento + immobilità", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(checked = manDownEnabled, onCheckedChange = { logChange("malore_enabled", manDownEnabled.toString(), it.toString()); manDownEnabled = it; savePrefs() },
                        colors = SwitchDefaults.colors(checkedThumbColor = SoloSafeRed, checkedTrackColor = SoloSafeRed.copy(alpha = 0.3f)))
                }
                if (manDownEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Angolazione: ${manDownAngle.roundToInt()}°", color = TextSecondary, fontSize = 12.sp)
                    Slider(value = manDownAngle, onValueChange = { manDownAngle = it },
                        onValueChangeFinished = { savePrefs(); logChange("malore_angle", "", "${manDownAngle.roundToInt()}") },
                        valueRange = 20f..90f, steps = 13,
                        colors = SliderDefaults.colors(thumbColor = SoloSafeRed, activeTrackColor = SoloSafeRed))
                }
            }

            // Fall
            SettingsCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Caduta (Fall Detection)", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Picco accelerometro", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(checked = fallEnabled, onCheckedChange = { logChange("fall_enabled", fallEnabled.toString(), it.toString()); fallEnabled = it; savePrefs() },
                        colors = SwitchDefaults.colors(checkedThumbColor = SoloSafeRed, checkedTrackColor = SoloSafeRed.copy(alpha = 0.3f)))
                }
                if (fallEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Soglia: ${"%.1f".format(fallThresholdG)}g", color = TextSecondary, fontSize = 12.sp)
                    Slider(value = fallThresholdG, onValueChange = { fallThresholdG = it },
                        onValueChangeFinished = { savePrefs(); logChange("fall_threshold_g", "", "${"%.1f".format(fallThresholdG)}") },
                        valueRange = 1.5f..4.0f, steps = 24,
                        colors = SliderDefaults.colors(thumbColor = SoloSafeRed, activeTrackColor = SoloSafeRed))
                }
            }

            // Immobility
            SettingsCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Immobilità", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Fermo troppo a lungo", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(checked = immobilityEnabled, onCheckedChange = { logChange("immobility_enabled", immobilityEnabled.toString(), it.toString()); immobilityEnabled = it; savePrefs() },
                        colors = SwitchDefaults.colors(checkedThumbColor = SoloSafeRed, checkedTrackColor = SoloSafeRed.copy(alpha = 0.3f)))
                }
                if (immobilityEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Pre-allarme dopo: ${immobilitySeconds.roundToInt()}s", color = TextSecondary, fontSize = 12.sp)
                    Slider(value = immobilitySeconds, onValueChange = { immobilitySeconds = it },
                        onValueChangeFinished = { savePrefs(); logChange("immobility_seconds", "", "${immobilitySeconds.roundToInt()}") },
                        valueRange = 30f..300f, steps = 26,
                        colors = SliderDefaults.colors(thumbColor = SoloSafeRed, activeTrackColor = SoloSafeRed))
                }
            }

            // SOS
            SettingsCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("SOS Manuale", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Sempre attivo — non disattivabile", color = Protected, fontSize = 11.sp)
                    }
                    Switch(checked = true, onCheckedChange = { /* non disattivabile */ }, enabled = false,
                        colors = SwitchDefaults.colors(checkedThumbColor = Protected, checkedTrackColor = Protected.copy(alpha = 0.3f)))
                }
            }

            // === SECTION 2: AUTHORIZED NUMBERS ===
            SectionHeader("Numeri Autorizzati (risposta automatica)")

            SettingsCard {
                authNumbers.forEach { number ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Phone, contentDescription = null, tint = Protected, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(number, color = TextPrimary, fontSize = 14.sp)
                        }
                        IconButton(
                            onClick = {
                                authNumbers = authNumbers - number
                                prefs.edit().putString("authorized_numbers", authNumbers.joinToString(",")).commit()
                            },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Rimuovi", tint = Alarm, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                if (authNumbers.isEmpty()) {
                    Text("Nessun numero autorizzato", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newNumber,
                        onValueChange = { newNumber = it },
                        placeholder = { Text("+39 333 1234567", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = TextPrimary),
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = {
                        if (newNumber.isNotBlank()) {
                            authNumbers = authNumbers + newNumber.trim()
                            prefs.edit().putString("authorized_numbers", authNumbers.joinToString(",")).commit()
                            newNumber = ""
                        }
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "Aggiungi", tint = Protected)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        loadingNumbers = true
                        scope.launch {
                            try {
                                val phones = withContext(Dispatchers.IO) { supabase.getAuthorizedPhones(operatorId) }
                                if (phones.isNotEmpty()) {
                                    authNumbers = (authNumbers + phones).distinct()
                                    prefs.edit().putString("authorized_numbers", authNumbers.joinToString(",")).commit()
                                }
                            } catch (_: Exception) {}
                            loadingNumbers = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    enabled = !loadingNumbers,
                ) {
                    if (loadingNumbers) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = TextSecondary)
                    } else {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Carica da Supabase", color = TextSecondary, fontSize = 12.sp)
                }
            }

            // === SECTION 3: INFO ===
            SectionHeader("Info Dispositivo")

            SettingsCard {
                InfoRow("Operatore", operatorName)
                InfoRow("Preset", preset)
                InfoRow("ID", operatorId.take(8) + "...")
                InfoRow("Versione", "1.0.0")
            }

            // Reconfigure button
            Button(
                onClick = {
                    prefs.edit().clear().commit()
                    (context as Activity).apply {
                        val intent = Intent(this, MainActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finish()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A0000)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = Alarm, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Riconfigura dispositivo", color = Alarm, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, color = SoloSafeRed, fontWeight = FontWeight.Bold, fontSize = 13.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), content = content)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = TextSecondary, fontSize = 13.sp)
        Text(value, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
