package com.github.meetingrecorder

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
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
    @get:Rule val compose = RobolectricComposeRule()

    @Test fun `Qwen progress stays visible when switching to audio and transcript`() {
        val vm = mock<MeetingDetailViewModel>()
        val transcript = MutableStateFlow<String?>(null)
        whenever(vm.defaultSummaryOptions).thenReturn(com.github.meetingrecorder.data.SummaryOptions())
        whenever(vm.preview).thenReturn(MutableStateFlow(null))
        whenever(vm.versions).thenReturn(MutableStateFlow(emptyList()))
        whenever(vm.transcript).thenReturn(transcript)
        whenever(vm.notes).thenReturn(MutableStateFlow(null))
        whenever(vm.hasOriginal).thenReturn(MutableStateFlow(false))
        whenever(vm.usingOriginal).thenReturn(MutableStateFlow(false))
        whenever(vm.audioWarning).thenReturn(MutableStateFlow(null))
        whenever(vm.hasAudio).thenReturn(MutableStateFlow(true))
        whenever(vm.genState).thenReturn(MutableStateFlow(GenState.Processing("Qwen3-ASR 正在识别")))
        whenever(vm.isPlaying).thenReturn(MutableStateFlow(false))
        whenever(vm.waveform).thenReturn(MutableStateFlow(emptyList()))
        whenever(vm.displayTitle).thenReturn(MutableStateFlow("会议录音"))
        whenever(vm.positionMs).thenReturn(MutableStateFlow(0))
        whenever(vm.durationMs).thenReturn(MutableStateFlow(60_000))
        whenever(vm.currentTime).thenReturn(MutableStateFlow("00:00"))
        whenever(vm.totalTime).thenReturn(MutableStateFlow("01:00"))
        compose.setContent { MaterialTheme { MeetingDetailScreen("/fixture", {}, vm) } }
        compose.onNodeWithText("音频").assertIsSelected()
        val audioLeft = compose.onNodeWithText("音频").fetchSemanticsNode().boundsInRoot.left
        val transcriptLeft = compose.onNodeWithText("转写").fetchSemanticsNode().boundsInRoot.left
        val notesLeft = compose.onNodeWithText("总结").fetchSemanticsNode().boundsInRoot.left
        org.junit.Assert.assertTrue(audioLeft < transcriptLeft && transcriptLeft < notesLeft)
        compose.onNodeWithText("Qwen3-ASR 正在识别").assertIsDisplayed()
        compose.onNodeWithText("音频").performClick()
        compose.onNodeWithText("Qwen3-ASR 正在识别").assertIsDisplayed()
        compose.onNodeWithText("转写").performClick()
        compose.onNodeWithText("Qwen3-ASR 正在识别").assertIsDisplayed()
        compose.runOnIdle { transcript.value = "Saved transcript" }
        compose.onNodeWithText("Qwen3-ASR 正在识别").assertIsDisplayed()
        compose.onNodeWithText("总结").performClick()
        compose.onNodeWithText("Qwen3-ASR 正在识别").assertIsDisplayed()
    }
}
