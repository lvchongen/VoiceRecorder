package com.github.meetingrecorder

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.github.meetingrecorder.data.MeetingTemplate
import com.github.meetingrecorder.ui.settings.PromptsTab
import com.github.meetingrecorder.ui.settings.SettingsViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.github.meetingrecorder.data.Config as AppConfig

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TemplatePromptsUiTest {
    @get:Rule val compose = RobolectricComposeRule()

    @Test fun `switching templates updates Chinese prompt and saving keeps edits separate`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("meeting_recorder", Context.MODE_PRIVATE).edit().clear().commit()
        val config = AppConfig(context)
        val viewModel = SettingsViewModel(config, mock(), mock())
        compose.mainClock.autoAdvance = false
        lateinit var focusManager: androidx.compose.ui.focus.FocusManager
        compose.setContent {
            focusManager = androidx.compose.ui.platform.LocalFocusManager.current
            MaterialTheme { PromptsTab(viewModel) }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText(MeetingTemplate.GENERAL.defaultPrompt).performTextReplacement("只整理确认结论")
        compose.onNodeWithText("通用会议").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("项目周会").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText(MeetingTemplate.WEEKLY.defaultPrompt).assertExists()
        compose.runOnIdle { focusManager.clearFocus() }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        assertEquals("只整理确认结论", AppConfig(context).templatePrompt(MeetingTemplate.GENERAL))
        assertEquals(MeetingTemplate.WEEKLY.defaultPrompt, AppConfig(context).templatePrompt(MeetingTemplate.WEEKLY))
    }
}
