package com.github.meetingrecorder

import com.github.meetingrecorder.data.LocalChatFormat
import com.github.meetingrecorder.data.LocalSummaryModel
import com.github.meetingrecorder.data.LocalSummaryModelStore
import com.github.meetingrecorder.data.LocalTextEngine
import com.github.meetingrecorder.data.MeetingTemplate
import com.github.meetingrecorder.data.SummaryOptions
import com.github.meetingrecorder.data.SummaryProvider
import com.github.meetingrecorder.data.TranscriptionProvider
import com.github.meetingrecorder.data.WhisperModel
import com.github.meetingrecorder.data.buildSummaryPrompt
import com.github.meetingrecorder.data.localChatPrompt
import com.github.meetingrecorder.data.processingConfigurationError
import com.github.meetingrecorder.data.splitLocalText
import com.github.meetingrecorder.data.summarizeLocally
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class LocalSummaryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `local summaries with local transcription require no cloud credentials`() {
        assertNull(processingConfigurationError(true, "", SummaryProvider.LOCAL, "", "", TranscriptionProvider.WHISPER))
        assertNotNull(processingConfigurationError(true, "", SummaryProvider.LOCAL, "", "", TranscriptionProvider.GEMINI))
    }

    @Test fun `chat envelopes follow each model family and disable Qwen thinking`() {
        assertEquals("<|assistant|>", localChatPrompt(LocalChatFormat.PHI4_MINI, "原文").takeLast(13))
        assertTrue(localChatPrompt(LocalChatFormat.GEMMA4, "原文").endsWith("<|turn>model\n"))
        assertFalse(localChatPrompt(LocalChatFormat.GEMMA4, "原文").contains("<|think|>"))
        assertTrue(localChatPrompt(LocalChatFormat.QWEN35, "原文").endsWith("<think>\n\n</think>\n\n"))
        assertTrue(localChatPrompt(LocalChatFormat.CHATML, "原文").endsWith("<|im_start|>assistant\n"))
    }

    @Test fun `split preserves every character including unicode and final utterance`() {
        val source = ("中文 English 😀\n".repeat(100)) + "最后决定取消发布"
        val parts = splitLocalText(source) { it.length <= 101 }
        assertEquals(source, parts.joinToString(""))
        assertTrue(parts.all { it.length <= 101 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
    }

    @Test fun `long meeting includes the last part and merges all extracted parts`() = runTest {
        val calls = mutableListOf<String>()
        val engine = FakeEngine(calls)
        val source = "讨论背景\n".repeat(2000) + "最终决定取消发布"
        val result = summarizeLocally(source, SummaryOptions(), engine, { "SUMMARY:$it" }, {})
        assertEquals("final", result)
        val extraction = calls.filter { !it.startsWith("SUMMARY:") }
        assertTrue(extraction.size > 1)
        assertTrue(extraction.last().endsWith("最终决定取消发布"))
        for (i in extraction.indices) assertTrue(calls.last().contains("fact-${i + 1}"))
    }

    @Test fun `translation preserves chunks without summary reduction`() = runTest {
        val calls = mutableListOf<String>()
        val source = "中文对话\n".repeat(800)
        summarizeLocally(source, SummaryOptions(template = MeetingTemplate.TRANSLATION), FakeEngine(calls), { "TRANSLATE:$it" }, {})
        assertTrue(calls.size > 1)
        assertTrue(calls.all { it.startsWith("TRANSLATE:") })
        assertEquals(source, calls.joinToString("") { it.removePrefix("TRANSLATE:") })
    }

    @Test fun `all language content and fragment limitations are explicit in prompt`() {
        val text = "中文决定保留原文。English proposal rejected."
        val prompt = buildSummaryPrompt(text, SummaryOptions())
        assertTrue(prompt.contains(text))
        assertTrue(prompt.contains("完整处理所有语言"))
        assertTrue(prompt.contains("依据现有片段"))
        assertTrue(prompt.contains("不把整个结果包进代码块"))
    }

    @Test fun `catalog has distinct pinned checksummed models`() {
        val models = LocalSummaryModel.all
        assertEquals(models.size, models.map { it.id }.toSet().size)
        assertTrue(models.all { it.revision.matches(Regex("[a-f0-9]{40}")) && it.asset.sha256.matches(Regex("[a-f0-9]{64}")) })
        assertTrue(models.any { it.format == LocalChatFormat.GEMMA4 })
        assertTrue(models.any { it.format == LocalChatFormat.PHI4_MINI })
    }

    @Test fun `summary download verifies hash and failed download cannot replace valid file`() = runTest {
        val body = "valid model"
        val hash = MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
        val model = LocalSummaryModel("test", "test", "test/repo", "revision", WhisperModel("test", "model.gguf", body.length.toLong(), hash), LocalChatFormat.CHATML)
        val directory = folder.newFolder()
        MockWebServer().use { server ->
            val store = LocalSummaryModelStore(directory, listOf(model)) { server.url("/model").toString() }
            assertFalse(store.installed(model))
            server.enqueue(MockResponse().setBody(body))
            store.download(model) {}
            assertTrue(store.installed(model))
            server.enqueue(MockResponse().setBody("invalid mdl"))
            try {
                store.download(model) {}
                fail("must reject corrupt model")
            } catch (_: IllegalStateException) { }
            store.withModel(model) { assertEquals(body, it.readText()) }
            assertFalse(directory.resolve("model.gguf.part").exists())
            store.delete(model)
            assertFalse(store.installed(model))
        }
    }

    private class FakeEngine(private val calls: MutableList<String>) : LocalTextEngine {
        override val contextSize = 5000
        override fun tokenCount(prompt: String) = prompt.length
        override suspend fun complete(prompt: String, maxTokens: Int, onStatus: (String) -> Unit): String {
            assertTrue(prompt.length + maxTokens < contextSize)
            calls += prompt
            return if (prompt.startsWith("SUMMARY:")) "final" else "fact-${calls.size}"
        }
        override fun close() { }
    }
}
