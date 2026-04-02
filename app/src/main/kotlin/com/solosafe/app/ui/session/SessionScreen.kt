package com.solosafe.app.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.solosafe.app.ui.theme.*

@Composable
fun SessionScreen(
    state: SessionUiState,
    onSelectType: (String) -> Unit,
    onSelectDuration: (Int?) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .systemBarsPadding()
            .padding(24.dp),
    ) {
        // Back + title
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Indietro", tint = TextSecondary)
            }
            Text(
                text = when (state.step) {
                    1 -> "Tipo sessione"
                    2 -> "Durata prevista"
                    else -> "Conferma"
                },
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // Step indicator
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
            (1..3).forEach { step ->
                Box(
                    modifier = Modifier
                        .size(if (step == state.step) 10.dp else 8.dp)
                        .background(
                            if (step <= state.step) SoloSafeRed else Border,
                            shape = RoundedCornerShape(50),
                        )
                )
                if (step < 3) Spacer(modifier = Modifier.width(8.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (state.step) {
            1 -> {
                SessionTypeCard("Turno", "Durata dichiarata con scadenza", Icons.Default.Schedule, "#3B82F6") { onSelectType("turno") }
                SessionTypeCard("Intervento", "Avvio rapido — notifica responsabile", Icons.Default.Engineering, "#F39C12") { onSelectType("intervento") }
                SessionTypeCard("Continua", "Senza scadenza — H24", Icons.Default.AllInclusive, "#8B5CF6") { onSelectType("continua") }
                SessionTypeCard("Spazio Confinato", "Checklist + presidio esterno", Icons.Default.Warning, "#E74C3C") { onSelectType("spazio_confinato") }
            }
            2 -> {
                Text("Seleziona la durata del turno", color = TextSecondary, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DurationButton("4h", Modifier.weight(1f)) { onSelectDuration(4) }
                    DurationButton("8h", Modifier.weight(1f)) { onSelectDuration(8) }
                    DurationButton("12h", Modifier.weight(1f)) { onSelectDuration(12) }
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { onSelectDuration(8) }, // TODO: custom picker
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                ) {
                    Text("Personalizzata...")
                }
            }
            3 -> {
                // Confirmation
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("Riepilogo sessione", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        ConfirmRow("Tipo", state.sessionType?.replaceFirstChar { it.uppercase() } ?: "—")
                        ConfirmRow("Durata", state.durationHours?.let { "${it}h" } ?: "Continua")
                        ConfirmRow("Preset", state.preset)
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = onConfirm,
                    enabled = !state.isStarting,
                    colors = ButtonDefaults.buttonColors(containerColor = Protected),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    if (state.isStarting) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("AVVIA PROTEZIONE", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionTypeCard(title: String, subtitle: String, icon: ImageVector, colorHex: String, onClick: () -> Unit) {
    val color = Color(android.graphics.Color.parseColor(colorHex))
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Surface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(44.dp).background(color.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(subtitle, color = TextSecondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun DurationButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Surface),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.height(64.dp),
    ) {
        Text(label, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
    }
}

@Composable
private fun ConfirmRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = TextSecondary, fontSize = 14.sp)
        Text(value, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    }
}
