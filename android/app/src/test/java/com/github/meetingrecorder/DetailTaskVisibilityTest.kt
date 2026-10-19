package com.github.meetingrecorder

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.meetingrecorder.ui.detail.GenState
import com.github.meetingrecorder.ui.detail.MeetingDetailScreen
import com.github.meetingrecorder.ui.detail.MeetingDetailViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DetailTaskVisibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `Qwen progress stays visible when switching to audio and transcript`() {
        val vm = mock<MeetingDetailViewModel>()
        val transcript = MutableStateFlow<String?>(null)
        whenever(vm.transcript).thenReturn(transcript)
        whenever(vm.notes).thenReturn(MutableStateFlow(null))
        whenever(vm.hasOriginal).thenReturn(MutableStateFlow(false))
        whenever(vm.usingOriginal).thenReturn(MutableStateFlow(false))
        whenever(vm.audioWarning).thenReturn(MutableStateFlow(null))
        whenever(vm.hasAudio).thenReturn(MutableStateFlow(true))
        whenever(vm.genState).thenReturn(MutableStateFlow(GenState.Processing("Qwen3-ASR 正在识别")))
        whenever(vm.isPlaying).thenReturn(MutableStateFlow(false))
        whenever(vm.currentTime).thenReturn(MutableStateFlow("00:00"))
        whenever(vm.totalTime).thenReturn(MutableStateFlow("01:00"))
        compose.setContent { MaterialTheme { MeetingDetailScreen("/fixture", {}, vm) } }
        compose.onNodeWithText("Qwen3-ASR 正在识别").assertIsDisplayed()
        compose.onNodeWithText("Audio").performClick()
        compose.onNodeWithText("Qwen3-ASR 正在识别").assertIsDisplayed()
        compose.onNodeWithText("Transcript").performClick()
        compose.onNodeWithText("Qwen3-ASR 正在识别").assertIsDisplayed()
        compose.runOnIdle { transcript.value = "Saved transcript" }
        compose.onNodeWithText("Qwen3-ASR 正在识别").assertIsDisplayed()
        compose.onNodeWithText("Notes").performClick()
        compose.onNodeWithText("Qwen3-ASR 正在识别").assertIsDisplayed()
    }
}
