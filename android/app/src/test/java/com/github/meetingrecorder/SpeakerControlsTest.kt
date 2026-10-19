package com.github.meetingrecorder

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    @Test fun `voiceprint management presents simple actions without enrollment steps`() {
        val vm = mock<SpeakerViewModel>()
        val profile = VoiceProfile(name = "未命名 1", samples = listOf(sample))
        whenever(vm.state).thenReturn(MutableStateFlow(SpeakerUiState(profiles = listOf(profile), modelsReady = true)))
        compose.setContent { MaterialTheme { VoiceprintPanel(vm) } }
        compose.onNodeWithText("未命名 1").assertIsDisplayed()
        compose.onNodeWithText("命名").assertIsDisplayed()
        compose.onNodeWithText("更新").assertIsDisplayed()
        compose.onNodeWithText("试听").assertIsDisplayed()
        compose.onNodeWithText("确认保存").assertDoesNotExist()
        compose.onNodeWithText("分析说话人").assertDoesNotExist()
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

    @Test fun `update opens direct recording instead of a file picker`() {
        val vm = mock<SpeakerViewModel>()
        val profile = VoiceProfile(name = "张三", samples = listOf(sample))
        whenever(vm.state).thenReturn(MutableStateFlow(SpeakerUiState(profiles = listOf(profile), modelsReady = true)))
        compose.setContent { MaterialTheme { VoiceprintPanel(vm) } }
        compose.onNodeWithText("更新").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("录制声纹").assertIsDisplayed()
        compose.onNodeWithText("为「张三」补充声音样本").assertIsDisplayed()
        compose.onNodeWithText("开始录制").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("录制声纹").assertDoesNotExist()
        verify(vm, never()).updateRecordedVoiceSample(any(), any())
    }

    @Test fun `add starts with recording before asking for a name`() {
        val vm = mock<SpeakerViewModel>()
        whenever(vm.state).thenReturn(MutableStateFlow(SpeakerUiState(modelsReady = true)))
        compose.setContent { MaterialTheme { VoiceprintPanel(vm) } }
        compose.onNodeWithText("添加").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("录制声纹").assertIsDisplayed()
        compose.onNodeWithText("第一步：录制本人的声音").assertIsDisplayed()
        compose.onNodeWithText("开始录制").assertIsDisplayed()
        compose.onNodeWithText("姓名").assertDoesNotExist()
        compose.onNodeWithText("保存声纹").assertDoesNotExist()
        compose.onNodeWithText("取消").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("录制声纹").assertDoesNotExist()
        verify(vm, never()).addRecordedVoiceSample(any(), any())
    }
}
