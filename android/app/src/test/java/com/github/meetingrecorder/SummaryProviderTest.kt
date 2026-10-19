package com.github.meetingrecorder

import android.content.Context
import android.content.SharedPreferences
import com.github.meetingrecorder.data.Config
import com.github.meetingrecorder.data.SelectedSummaryClient
import com.github.meetingrecorder.data.SummaryClient
import com.github.meetingrecorder.data.SummaryProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SummaryProviderTest {
    @Test
    fun `existing preferences retain Gemini and unknown provider falls back`() {
        val prefs = mock<SharedPreferences>()
        val context = mock<Context>()
        whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
        whenever(prefs.getString(any(), anyOrNull())).thenAnswer { it.arguments[1] }
        val config = Config(context)
        assertEquals(SummaryProvider.GEMINI, config.summaryProvider)
        assertEquals(Config.DEFAULT_DEEPSEEK_MODEL, config.deepSeekModel)
        assertEquals(com.github.meetingrecorder.data.MeetingTemplate.GENERAL, config.summaryTemplate)
        assertEquals(com.github.meetingrecorder.data.SummaryDetail.STANDARD, config.summaryDetail)
        whenever(prefs.getString("summary_template", "GENERAL")).thenReturn("future-template")
        whenever(prefs.getString("summary_detail", "STANDARD")).thenReturn("future-detail")
        assertEquals(com.github.meetingrecorder.data.MeetingTemplate.GENERAL, config.summaryTemplate)
        assertEquals(com.github.meetingrecorder.data.SummaryDetail.STANDARD, config.summaryDetail)
        whenever(prefs.getString("summary_provider", "GEMINI")).thenReturn("future-provider")
        assertEquals(SummaryProvider.GEMINI, config.summaryProvider)
    }

    @Test
    fun `provider switching routes both summary and title without restart`() = runTest {
        val config = mock<Config>()
        val gemini = FakeSummaryClient("gemini")
        val deepSeek = FakeSummaryClient("deepseek")
        val selected = SelectedSummaryClient(config, gemini, deepSeek)
        whenever(config.summaryProvider).thenReturn(SummaryProvider.GEMINI)
        assertEquals("gemini:transcript", selected.summarize("transcript"))
        whenever(config.summaryProvider).thenReturn(SummaryProvider.DEEPSEEK)
        assertEquals("deepseek:transcript", selected.summarize("transcript"))
        assertEquals("deepseek:notes", selected.generateTitle("notes"))
        assertEquals(1, gemini.calls)
        assertEquals(2, deepSeek.calls)
    }

    private class FakeSummaryClient(private val name: String) : SummaryClient {
        var calls = 0
        override suspend fun summarize(transcript: String, onStatus: (String) -> Unit): String {
            calls++
            return "$name:$transcript"
        }
        override suspend fun generateTitle(notes: String, onStatus: (String) -> Unit): String {
            calls++
            return "$name:$notes"
        }
    }
}
