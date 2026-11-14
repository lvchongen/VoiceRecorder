package com.github.meetingrecorder.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Explicit neutral roles prevent Material defaults from reintroducing purple accents.
internal val SilverDark = darkColorScheme(
    primary = Color(0xFFBAC4CC), onPrimary = Color(0xFF192027),
    primaryContainer = Color(0xFF353E46), onPrimaryContainer = Color(0xFFE4E9ED),
    secondary = Color(0xFFB4BEC6), onSecondary = Color(0xFF20272D),
    secondaryContainer = Color(0xFF343C43), onSecondaryContainer = Color(0xFFE3E7EB),
    tertiary = Color(0xFFBCC4CA), onTertiary = Color(0xFF20272D),
    tertiaryContainer = Color(0xFF343C43), onTertiaryContainer = Color(0xFFE3E7EB),
    background = Color(0xFF171C21), onBackground = Color(0xFFE7EBEE),
    surface = Color(0xFF171C21), onSurface = Color(0xFFE7EBEE),
    surfaceVariant = Color(0xFF30373E), onSurfaceVariant = Color(0xFFB5BEC6),
    surfaceTint = Color.Transparent,
    surfaceDim = Color(0xFF12171B), surfaceBright = Color(0xFF394149),
    surfaceContainerLowest = Color(0xFF11161A), surfaceContainerLow = Color(0xFF20262C),
    surfaceContainer = Color(0xFF252C32), surfaceContainerHigh = Color(0xFF30383F),
    surfaceContainerHighest = Color(0xFF3A434B),
    outline = Color(0xFF7D8992), outlineVariant = Color(0xFF3C454D),
    inverseSurface = Color(0xFFE7EBEE), inverseOnSurface = Color(0xFF242B31), inversePrimary = Color(0xFF4D606E),
)

internal val SilverLight = lightColorScheme(
    primary = Color(0xFF465B6A), onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E6EB), onPrimaryContainer = Color(0xFF263640),
    secondary = Color(0xFF52616C), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E9ED), onSecondaryContainer = Color(0xFF293640),
    tertiary = Color(0xFF56636C), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE4E9ED), onTertiaryContainer = Color(0xFF293640),
    background = Color(0xFFF5F7F8), onBackground = Color(0xFF20282F),
    surface = Color(0xFFF5F7F8), onSurface = Color(0xFF20282F),
    surfaceVariant = Color(0xFFE1E7EB), onSurfaceVariant = Color(0xFF58656F),
    surfaceTint = Color.Transparent,
    surfaceDim = Color(0xFFDCE2E6), surfaceBright = Color.White,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F3F5),
    surfaceContainer = Color(0xFFEBEFF2), surfaceContainerHigh = Color(0xFFE4E9ED),
    surfaceContainerHighest = Color(0xFFDCE3E8),
    outline = Color(0xFF74828D), outlineVariant = Color(0xFFD0D8DE),
    inverseSurface = Color(0xFF283139), inverseOnSurface = Color(0xFFEAF0F3), inversePrimary = Color(0xFFBAC4CC),
)

@Composable
fun MeetingRecorderTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) SilverDark else SilverLight,
        shapes = Shapes(extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp)),
        content = content,
    )
}
