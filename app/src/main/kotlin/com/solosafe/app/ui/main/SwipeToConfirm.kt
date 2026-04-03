package com.solosafe.app.ui.main

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.solosafe.app.ui.theme.*

/**
 * Swipe-to-confirm component.
 * Drag thumb from left to right past 80% to confirm.
 * Red color intensifies as you drag.
 */
@Composable
fun SwipeToConfirm(
    text: String = "Scorri per terminare",
    onConfirmed: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var offsetX by remember { mutableFloatStateOf(0f) }
    var trackWidth by remember { mutableFloatStateOf(1f) }
    var confirmed by remember { mutableStateOf(false) }

    val progress = (offsetX / trackWidth).coerceIn(0f, 1f)
    val bgColor by animateColorAsState(
        Color(
            red = (0.15f + progress * 0.55f).coerceIn(0f, 1f),
            green = 0.08f * (1f - progress),
            blue = 0.08f * (1f - progress),
            alpha = 1f,
        ), label = "swipeBg"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(bgColor)
            .pointerInput(Unit) {
                trackWidth = size.width.toFloat() - with(density) { 56.dp.toPx() }
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (progress > 0.8f && !confirmed) {
                            confirmed = true
                            // Haptic feedback
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
                        confirmed = false
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        offsetX = (offsetX + dragAmount).coerceIn(0f, trackWidth)
                    }
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // Text hint
        Text(
            "〉 $text",
            color = Color.White.copy(alpha = 0.4f * (1f - progress)),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.Center),
        )

        // Draggable thumb
        Box(
            modifier = Modifier
                .offset(x = with(density) { offsetX.toDp() })
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
