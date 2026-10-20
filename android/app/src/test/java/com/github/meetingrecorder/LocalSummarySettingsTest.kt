package com.github.meetingrecorder

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.meetingrecorder.data.LocalSummaryModel
import com.github.meetingrecorder.data.LocalSummaryModelStore
import com.github.meetingrecorder.data.SummaryProvider
import com.github.meetingrecorder.data.TranscriptionProvider
import com.github.meetingrecorder.ui.settings.SettingsScreen
import com.github.meetingrecorder.ui.settings.SettingsViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.github.meetingrecorder.data.Config as AppConfig

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalSummarySettingsTest {
    @get:Rule val compose = RobolectricComposeRule()

    @Test fun `downloaded model remains selectable and saving preserves model choice`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("meeting_recorder", Context.MODE_PRIVATE).edit().clear().commit()
        val config = AppConfig(context)
        config.summaryProvider = SummaryProvider.LOCAL
        config.localSummaryModel = "gemma4-e4b"
        val models = mock<LocalSummaryModelStore>()
        whenever(models.installed(LocalSummaryModel.selected("qwen35-2b"))).thenReturn(true)
        val vm = SettingsViewModel(config, mock(), mock(), models)
        compose.setContent { MaterialTheme { SettingsScreen({}, vm) } }
        compose.onNodeWithText("Qwen3.5 2B").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Q4_K_M · 1.28 GB · 已下载").assertExists()
        compose.onNodeWithText("保存").performScrollTo().performClick()
        assertEquals("qwen35-2b", AppConfig(context).localSummaryModel)
        assertEquals(SummaryProvider.LOCAL, AppConfig(context).summaryProvider)
    }

    @Test fun `Gemini transcription keeps its settings with local summaries`() {
        showProviders(TranscriptionProvider.GEMINI, SummaryProvider.LOCAL)
        compose.onNodeWithText("Gemini 密钥").assertExists()
        compose.onNodeWithText("Qwen3.5 2B").assertExists()
        compose.onNodeWithText("DeepSeek 密钥").assertDoesNotExist()
    }

    @Test fun `Gemini summary settings appear with local transcription`() {
        showProviders(TranscriptionProvider.WHISPER, SummaryProvider.GEMINI)
        compose.onNodeWithText("Gemini 密钥").assertExists()
        compose.onNodeWithText("Qwen3.5 2B").assertDoesNotExist()
        compose.onNodeWithText("DeepSeek 密钥").assertDoesNotExist()
    }

    @Test fun `DeepSeek settings exclude other summary providers`() {
        showProviders(TranscriptionProvider.WHISPER, SummaryProvider.DEEPSEEK)
        compose.onNodeWithText("DeepSeek 密钥").assertExists()
        compose.onNodeWithText("Gemini 密钥").assertDoesNotExist()
        compose.onNodeWithText("Qwen3.5 2B").assertDoesNotExist()
    }

    @Test fun `local providers hide cloud configuration`() {
        compose.mainClock.autoAdvance = false
        showProviders(TranscriptionProvider.WHISPER, SummaryProvider.LOCAL)
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Gemini 密钥").assertDoesNotExist()
        compose.onNodeWithText("DeepSeek 密钥").assertDoesNotExist()
        compose.onNodeWithText("Qwen3.5 2B").assertExists()
    }

    private fun showProviders(transcription: TranscriptionProvider, summary: SummaryProvider) {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("meeting_recorder", Context.MODE_PRIVATE).edit().clear().commit()
        val config = AppConfig(context)
        config.transcriptionProvider = transcription
        config.summaryProvider = summary
        val vm = SettingsViewModel(config, mock(), mock(), mock())
        compose.setContent { MaterialTheme { SettingsScreen({}, vm) } }
    }
}
