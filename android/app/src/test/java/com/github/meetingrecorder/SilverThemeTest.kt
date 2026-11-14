package com.github.meetingrecorder

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.github.meetingrecorder.ui.theme.SilverDark
import com.github.meetingrecorder.ui.theme.SilverLight
import org.junit.Assert.assertTrue
import org.junit.Test

class SilverThemeTest {
    @Test fun `text stays readable on both neutral themes`() {
        listOf(SilverDark, SilverLight).forEach { scheme ->
            listOf(scheme.onSurface to scheme.surface, scheme.onSurfaceVariant to scheme.surfaceContainer, scheme.onPrimaryContainer to scheme.primaryContainer, scheme.primary to scheme.surfaceContainerHighest).forEach { (text, background) ->
                assertTrue("Insufficient text contrast: $text / $background", contrast(text, background) >= 4.5)
            }
        }
    }

    private fun contrast(a: Color, b: Color): Float {
        val x = a.luminance()
        val y = b.luminance()
        return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
    }
}
