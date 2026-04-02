package com.solosafe.app.ui.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.solosafe.app.ui.theme.*

@Composable
fun WelcomeScreen(
    onConfigureDevice: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            // Logo
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = SoloSafeRed,
                modifier = Modifier.size(80.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Shield,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(44.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "SoloSafe",
                color = TextPrimary,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
            )

            Text(
                "Worker Safety Platform",
                color = TextSecondary,
                fontSize = 14.sp,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                "Proteggi i lavoratori isolati\ncon monitoraggio in tempo reale",
                color = TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp,
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Configure button
            Button(
                onClick = onConfigureDevice,
                colors = ButtonDefaults.buttonColors(containerColor = SoloSafeRed),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text("Configura dispositivo", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }

            Spacer(modifier = Modifier.height(48.dp))

            Text(
                "Chiedi al responsabile sicurezza\nil codice QR di configurazione",
                color = Color(0xFF4A5568),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
