package com.github.meetingrecorder

import android.app.Application
import android.content.Context
import com.github.meetingrecorder.data.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.github.meetingrecorder.data.Config as AppConfig

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThemePreferencesTest {
    @Test
    fun `template prompts persist separately and reset without losing legacy supplement`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("meeting_recorder", Context.MODE_PRIVATE).edit().clear().commit()
        val config = AppConfig(context)
        val weekly = com.github.meetingrecorder.data.MeetingTemplate.WEEKLY
        val general = com.github.meetingrecorder.data.MeetingTemplate.GENERAL
        config.summarizationPrompt = "原有补充"
        config.setTemplatePrompt(weekly, "按负责人整理")
        val reopened = AppConfig(context)
        assertEquals("按负责人整理", reopened.templatePrompt(weekly))
        assertEquals(general.defaultPrompt, reopened.templatePrompt(general))
        assertEquals("原有补充", reopened.summarizationPrompt)
        reopened.setTemplatePrompt(weekly, weekly.defaultPrompt)
        assertEquals(weekly.defaultPrompt, AppConfig(context).templatePrompt(weekly))
        reopened.setTemplatePrompt(weekly, "  ")
        assertEquals(weekly.defaultPrompt, reopened.templatePrompt(weekly))
    }

    @Test
    fun `denoising defaults off and persists independently of recognition model`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("meeting_recorder", Context.MODE_PRIVATE).edit().clear().commit()
        val config = AppConfig(context)
        val model = config.whisperModel
        assertEquals(false, config.recordingDenoiseEnabled)
        config.recordingDenoiseEnabled = true
        val reopened = AppConfig(context)
        assertEquals(true, reopened.recordingDenoiseEnabled)
        assertEquals(model, reopened.whisperModel)
    }

    @Test
    fun `theme changes emit immediately and survive reopening without changing keys`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("meeting_recorder", Context.MODE_PRIVATE).edit().clear().commit()
        val config = AppConfig(context)
        config.apiKey = "test-key"
        assertEquals(ThemeMode.SYSTEM, config.themeMode.value)
        config.setThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, config.themeMode.value)
        val reopened = AppConfig(context)
        assertEquals(ThemeMode.LIGHT, reopened.themeMode.value)
        assertEquals("test-key", reopened.apiKey)
        reopened.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, AppConfig(context).themeMode.value)
    }
}
