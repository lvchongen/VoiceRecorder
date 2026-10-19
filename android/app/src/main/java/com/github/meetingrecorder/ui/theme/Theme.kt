package com.github.meetingrecorder.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Indigo primary — matches the existing Linux app colour scheme
private val IndigoPrimary = Color(0xFF6366F1)
private val IndigoOnPrimary = Color(0xFFFFFFFF)
private val IndigoPrimaryContainer = Color(0xFF4338CA)
private val IndigoOnPrimaryContainer = Color(0xFFE0E7FF)

private val DarkColorScheme = darkColorScheme(
    primary = IndigoPrimary,
    onPrimary = IndigoOnPrimary,
    primaryContainer = IndigoPrimaryContainer,
    onPrimaryContainer = IndigoOnPrimaryContainer,
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF4338CA),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF24116A),
)

@Composable
fun MeetingRecorderTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        content = content,
    )
}
