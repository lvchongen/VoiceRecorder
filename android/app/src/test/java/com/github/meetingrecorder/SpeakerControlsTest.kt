package com.github.meetingrecorder

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.github.meetingrecorder.speakers.MeetingSpeaker
import com.github.meetingrecorder.speakers.SpeakerMeeting
import com.github.meetingrecorder.speakers.SpeakerMeetingPanel
import com.github.meetingrecorder.speakers.SpeakerUiState
import com.github.meetingrecorder.speakers.SpeakerViewModel
import com.github.meetingrecorder.speakers.VoiceProfile
import com.github.meetingrecorder.speakers.VoiceSample
import com.github.meetingrecorder.speakers.VoiceprintPanel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SpeakerControlsTest {
    @get:Rule val compose = RobolectricComposeRule()

    // Advance transitions explicitly; continuous lazy-layout/cursor animations do not define test completion.
    @Before fun freezeAnimations() {
        compose.mainClock.autoAdvance = false
    }
    private val sample = VoiceSample(embedding = listOf(1f, 0f), model = "test", source = "fixture", startMs = 0, durationMs = 3000)

    @Test fun `a suggested identity is not applied and manual name does not enroll by default`() {
        val vm = mock<SpeakerViewModel>()
        val meeting = SpeakerMeeting(sourceName = "audio", sourceHash = "hash", speakers = listOf(MeetingSpeaker("A", samples = listOf(sample))), turns = emptyList())
        whenever(vm.state).thenReturn(MutableStateFlow(SpeakerUiState(meeting = meeting, profiles = listOf(VoiceProfile(name = "Candidate", samples = listOf(sample))), modelsReady = true)))
        compose.setContent { MaterialTheme { SpeakerMeetingPanel("/fixture", {}, viewModel = vm) } }
        compose.onNodeWithText("识别并命名（可选）").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Candidate · 相似度 1.00").assertIsDisplayed()
        verify(vm, never()).confirm(any(), any(), anyOrNull(), any(), anyOrNull())
        compose.onNodeWithText("姓名").performTextInput("张三")
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("确认保存").performClick()
        compose.mainClock.advanceTimeBy(500)
        verify(vm).confirm("A", "张三", null, false, setOf(sample.id))
    }

    @Test fun `voiceprint deletion requires explicit confirmation`() {
        val vm = mock<SpeakerViewModel>()
        val profile = VoiceProfile(name = "张三", samples = listOf(sample))
        whenever(vm.state).thenReturn(MutableStateFlow(SpeakerUiState(profiles = listOf(profile))))
        compose.setContent { MaterialTheme { VoiceprintPanel(vm) } }
        compose.onNodeWithText("删除声纹").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("确认删除").assertIsDisplayed()
        verify(vm, never()).deleteProfile(any())
        compose.onNodeWithText("删除").performClick()
        compose.mainClock.advanceTimeBy(500)
        verify(vm).deleteProfile(profile.id)
    }
}
