package com.solosafe.app.ui.permissions

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.solosafe.app.ui.theme.*

data class PermissionItem(
    val permission: String,
    val icon: ImageVector,
    val title: String,
    val description: String,
    val required: Boolean = true,
)

@Composable
fun PermissionScreen(onAllGranted: () -> Unit) {
    val context = LocalContext.current

    val permissions = remember {
        buildList {
            add(PermissionItem(Manifest.permission.ACCESS_FINE_LOCATION, Icons.Default.LocationOn, "Posizione GPS", "Per localizzare l'operatore in caso di emergenza", required = true))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                add(PermissionItem(Manifest.permission.POST_NOTIFICATIONS, Icons.Default.Notifications, "Notifiche", "Per gli allarmi e lo stato protezione", required = true))
            add(PermissionItem(Manifest.permission.SEND_SMS, Icons.Default.Sms, "Invio SMS", "Per inviare SMS di emergenza ai contatti", required = false))
            add(PermissionItem(Manifest.permission.CAMERA, Icons.Default.CameraAlt, "Fotocamera", "Per scansionare il QR di configurazione", required = false))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                add(PermissionItem(Manifest.permission.ACTIVITY_RECOGNITION, Icons.Default.DirectionsRun, "Riconoscimento attività", "Per rilevare cadute e immobilità", required = false))
        }
    }

    var currentIndex by remember { mutableIntStateOf(0) }
    var denied by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted || !permissions[currentIndex].required) {
            denied = false
            if (currentIndex < permissions.size - 1) {
                currentIndex++
            } else {
                onAllGranted()
            }
        } else {
            denied = true
        }
    }

    val current = permissions[currentIndex]

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .systemBarsPadding()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Progress
        Text("${currentIndex + 1} / ${permissions.size}", color = TextSecondary, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { (currentIndex + 1f) / permissions.size },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = SoloSafeRed,
            trackColor = Border,
        )

        Spacer(modifier = Modifier.weight(1f))

        // Icon
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = SoloSafeRed.copy(alpha = 0.15f),
            modifier = Modifier.size(80.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(current.icon, contentDescription = null, tint = SoloSafeRed, modifier = Modifier.size(40.dp))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(current.title, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(current.description, color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)

        if (!current.required) {
            Spacer(modifier = Modifier.height(4.dp))
            Text("(opzionale)", color = TextSecondary, fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.weight(1f))

        if (denied) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF2A0000),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Permesso negato", color = Alarm, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Questo permesso è necessario per la sicurezza.", color = TextSecondary, fontSize = 12.sp, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            })
                        },
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Apri impostazioni", color = TextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }

        Button(
            onClick = { denied = false; launcher.launch(current.permission) },
            colors = ButtonDefaults.buttonColors(containerColor = SoloSafeRed),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text("Consenti", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        if (!current.required) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = {
                denied = false
                if (currentIndex < permissions.size - 1) currentIndex++ else onAllGranted()
            }) {
                Text("Salta", color = TextSecondary, fontSize = 14.sp)
            }
        }
    }
}
