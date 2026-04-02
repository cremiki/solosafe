package com.solosafe.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SoloSafeRed = Color(0xFFE63946)
val Background = Color(0xFF0F1117)
val Surface = Color(0xFF1A1D27)
val Protected = Color(0xFF2ECC71)
val Standby = Color(0xFF95A5A6)
val Alarm = Color(0xFFE74C3C)
val Warning = Color(0xFFF39C12)
val TextPrimary = Color(0xFFECEFF4)
val TextSecondary = Color(0xFF8899AA)
val Border = Color(0xFF2A2D3E)

private val SoloSafeColors = darkColorScheme(
    primary = SoloSafeRed,
    onPrimary = Color.White,
    background = Background,
    surface = Surface,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    error = Alarm,
    outline = Border,
)

@Composable
fun SoloSafeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SoloSafeColors,
        content = content,
    )
}
