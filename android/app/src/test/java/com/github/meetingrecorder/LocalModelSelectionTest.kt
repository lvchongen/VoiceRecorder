package com.github.meetingrecorder

import android.app.Application
import android.content.Context
import com.github.meetingrecorder.data.QwenModelStore
import com.github.meetingrecorder.data.SelectedTranscriptionClient
import com.github.meetingrecorder.data.SummaryProvider
import com.github.meetingrecorder.data.TranscriptionClient
import com.github.meetingrecorder.data.TranscriptionProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import com.github.meetingrecorder.data.Config as AppConfig

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalModelSelectionTest {
    @Test
    fun `old Qwen selection migrates and unified list routes to correct engine`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("meeting_recorder", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("transcription_provider", "QWEN")
            .putString("deepseek_api_key", "preserved-key").commit()
        val config = AppConfig(context)
        assertEquals(TranscriptionProvider.WHISPER, config.transcriptionProvider)
        assertEquals(QwenModelStore.MODEL_ID, config.whisperModel)
        assertEquals("preserved-key", config.deepSeekApiKey)
        config.summaryProvider = SummaryProvider.DEEPSEEK
        fun engine(name: String) = object : TranscriptionClient {
            override suspend fun transcribe(audioFile: File, mimeType: String, onStatus: (String) -> Unit) = name
        }
        val selected = SelectedTranscriptionClient(config, engine("gemini"), engine("ali"), engine("whisper"), engine("qwen"))
        assertEquals("qwen", selected.transcribe(File("unused")))
        config.whisperModel = "medium"
        assertEquals("whisper", selected.transcribe(File("unused")))
        assertEquals("medium", AppConfig(context).whisperModel)
        config.whisperModel = QwenModelStore.MODEL_ID
        assertEquals("qwen", selected.transcribe(File("unused")))
    }
}
