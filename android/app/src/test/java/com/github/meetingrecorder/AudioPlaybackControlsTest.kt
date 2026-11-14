package com.github.meetingrecorder

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.github.meetingrecorder.ui.detail.AudioPlaybackControls
import com.github.meetingrecorder.ui.detail.playbackTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AudioPlaybackControlsTest {
    @get:Rule val compose = RobolectricComposeRule()

    @Test fun `slider seeks and skip buttons request ten seconds`() {
        compose.mainClock.autoAdvance = false
        var target = -1
        var skip = 0
        var stopped = false
        compose.setContent {
            MaterialTheme {
                AudioPlaybackControls(15_000, 60_000, false, onSeek = { target = it }, onSkip = { skip = it }, onPlayPause = {}, onStop = { stopped = true })
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("00:15").assertExists()
        compose.onNodeWithText("01:00").assertExists()
        compose.onNodeWithContentDescription("播放进度").performSemanticsAction(SemanticsActions.SetProgress) { it(30_000f) }
        assertEquals(30_000, target)
        compose.onNodeWithContentDescription("后退10秒").performClick()
        assertEquals(-10_000, skip)
        compose.onNodeWithContentDescription("快进10秒").performClick()
        assertEquals(10_000, skip)
        compose.onNodeWithText("停止并回到开头").performClick()
        assertEquals(true, stopped)
    }

    @Test fun `unprepared audio disables playback and seeking`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme { AudioPlaybackControls(0, 0, false, onSeek = {}, onSkip = {}, onPlayPause = {}, onStop = {}) }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithContentDescription("播放进度").assertIsNotEnabled()
        compose.onNodeWithContentDescription("播放").assertIsNotEnabled()
        compose.onNodeWithContentDescription("快进10秒").assertIsNotEnabled()
    }

    @Test fun `long recordings show hours without wrapping minutes`() {
        assertEquals("1:01:01", playbackTime(3_661_000))
        assertEquals("00:00", playbackTime(-1))
    }
}
