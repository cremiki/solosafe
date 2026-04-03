package com.solosafe.app.ui.main

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.solosafe.app.ui.theme.*
import kotlin.math.roundToInt

@Composable
fun SwipeToConfirm(
    text: String = "Scorri per terminare",
    onConfirmed: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val thumbSizePx = with(density) { 52.dp.toPx() }
    var trackWidthPx by remember { mutableFloatStateOf(0f) }
    var offsetX by remember { mutableFloatStateOf(0f) }

    val maxDrag = (trackWidthPx - thumbSizePx).coerceAtLeast(1f)
    val progress = (offsetX / maxDrag).coerceIn(0f, 1f)

    val draggableState = rememberDraggableState { delta ->
        offsetX = (offsetX + delta).coerceIn(0f, maxDrag)
    }

    val bgRed = (0.15f + progress * 0.55f).coerceIn(0f, 1f)
    val bgGreen = 0.08f * (1f - progress)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .onSizeChanged { trackWidthPx = it.width.toFloat() }
            .clip(RoundedCornerShape(28.dp))
            .background(Color(red = bgRed, green = bgGreen, blue = bgGreen))
            .draggable(
                state = draggableState,
                orientation = Orientation.Horizontal,
                onDragStopped = {
                    if (progress > 0.75f) {
                        // Confirmed
                        try {
                            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                (context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                            } else {
                                @Suppress("DEPRECATION")
                                context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as Vibrator
                            }
                            vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                        } catch (_: Exception) {}
                        onConfirmed()
                    }
                    offsetX = 0f
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        // Text
        Text(
            "〉 $text",
            color = Color.White.copy(alpha = (0.5f * (1f - progress)).coerceIn(0f, 1f)),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.Center),
        )

        // Thumb
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .size(52.dp)
                .padding(2.dp)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = Alarm,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}
