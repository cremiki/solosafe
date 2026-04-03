package com.solosafe.app.ui.registration

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.solosafe.app.SoloSafeApp
import com.solosafe.app.MainActivity
import com.solosafe.app.data.remote.SupabaseClient
import com.solosafe.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun FreeRegistrationScreen(
    onGoToQr: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showForm by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var company by remember { mutableStateOf("") }
    var privacyAccepted by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().background(Background).systemBarsPadding()
            .verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(40.dp))

        // Logo
        Surface(shape = RoundedCornerShape(20.dp), color = SoloSafeRed, modifier = Modifier.size(80.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Shield, contentDescription = null, tint = Color.White, modifier = Modifier.size(44.dp))
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("SoloSafe", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Worker Safety Platform", color = TextSecondary, fontSize = 13.sp)

        Spacer(modifier = Modifier.height(32.dp))

        if (!showForm) {
            // Choice screen
            Button(
                onClick = onGoToQr,
                colors = ButtonDefaults.buttonColors(containerColor = SoloSafeRed),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(modifier = Modifier.width(10.dp))
                Text("Ho un QR aziendale", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = { showForm = true },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Protected),
            ) {
                Icon(Icons.Default.PersonAdd, contentDescription = null)
                Spacer(modifier = Modifier.width(10.dp))
                Text("Prova gratuitamente", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text("La versione gratuita include:\n• SOS manuale con SMS\n• Localizzazione GPS\n• Pulsante emergenza", color = TextSecondary, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
        } else {
            // Registration form
            Text("Registrazione gratuita", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))

            error?.let {
                Text(it, color = Alarm, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            }

            FormField("Nome e Cognome *", name) { name = it }
            FormField("Email", email) { email = it }
            FormField("Telefono", phone) { phone = it }
            FormField("Nome azienda", company) { company = it }

            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = privacyAccepted, onCheckedChange = { privacyAccepted = it },
                    colors = CheckboxDefaults.colors(checkedColor = SoloSafeRed))
                Text("Accetto l'informativa privacy", color = TextSecondary, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (name.isBlank()) { error = "Nome obbligatorio"; return@Button }
                    if (!privacyAccepted) { error = "Accetta la privacy"; return@Button }
                    isLoading = true; error = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val supa = SupabaseClient()
                                val body = buildJsonObject {
                                    put("name", name.trim())
                                    if (email.isNotBlank()) put("email", email.trim())
                                    if (phone.isNotBlank()) put("phone", phone.trim())
                                    if (company.isNotBlank()) put("company", company.trim())
                                    put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
                                }
                                io.github.jan.supabase.postgrest.from(supa.client, "free_users").insert(body)
                            }
                            // Save free user state
                            context.getSharedPreferences(SoloSafeApp.PREFS_NAME, Context.MODE_PRIVATE).edit()
                                .putBoolean("free_user", true)
                                .putString("operator_name", name.trim())
                                .putBoolean("permissions_done", false)
                                .commit()
                            // Restart
                            (context as Activity).apply {
                                startActivity(Intent(this, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                })
                                finish()
                            }
                        } catch (e: Exception) {
                            error = "Errore: ${e.message}"
                            Log.e("SoloSafe", "Registration failed: ${e.message}")
                        } finally { isLoading = false }
                    }
                },
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = Protected),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (isLoading) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("Registrati gratis", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = { showForm = false }) {
                Text("Indietro", color = TextSecondary)
            }
        }
    }
}

@Composable
private fun FormField(label: String, value: String, onChange: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Text(label, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
        OutlinedTextField(
            value = value, onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 14.sp),
            singleLine = true,
        )
    }
}
