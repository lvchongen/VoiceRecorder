package com.github.meetingrecorder

import com.github.meetingrecorder.data.AliTranscriptionClient
import com.github.meetingrecorder.data.AudioChunk
import com.github.meetingrecorder.data.AudioChunks
import com.github.meetingrecorder.data.Config
import com.github.meetingrecorder.data.SummaryProvider
import com.github.meetingrecorder.data.TranscriptionProvider
import com.github.meetingrecorder.data.processingConfigurationError
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.util.Base64

class AliTranscriptionClientTest {
    private val server = MockWebServer()
    private val config = mock<Config>()
    private val delays = mutableListOf<Long>()

    @Before
    fun setup() {
        server.start()
        whenever(config.aliApiKey).thenReturn("ali-only-key")
        whenever(config.aliModel).thenReturn("qwen3-asr-flash")
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private fun client(count: Int = 1) = AliTranscriptionClient(
        config,
        AudioChunks { _, consume ->
            repeat(count) { consume(AudioChunk(byteArrayOf(1, 2, 3), it * 60L)) }
        },
        delayFn = { delays += it },
        baseUrlOverride = server.url("/").toString(),
    )

    private fun response(text: String, finish: String = "stop") = MockResponse().setBody(
        """{"choices":[{"finish_reason":"$finish","message":{"content":"$text"}}]}""",
    )

    @Test
    fun `segments use Alibaba key and audio payload and preserve order`() = runTest {
        server.enqueue(response("Hello"))
        server.enqueue(response("你好"))
        assertEquals("[00:00:00] Hello\n\n[00:01:00] 你好", client(2).transcribe(File("unused")))
        val request = server.takeRequest()
        assertEquals("Bearer ali-only-key", request.getHeader("Authorization"))
        assertEquals("/chat/completions", request.path)
        val json = JSONObject(request.body.readUtf8())
        assertEquals("qwen3-asr-flash", json.getString("model"))
        val data = json.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getJSONObject("input_audio").getString("data")
        assertEquals("data:audio/wav;base64," + Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)), data)
        assertFalse(json.getJSONObject("asr_options").has("language"))
    }

    @Test
    fun `rate limit retries the same segment`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(response("retry"))
        assertEquals("[00:00:00] retry", client().transcribe(File("unused")))
        assertEquals(listOf(2000L), delays)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `failed later segment cannot become successful partial transcript`() = runTest {
        server.enqueue(response("first"))
        server.enqueue(response("partial", "length"))
        assertTrue(runCatching { client(2).transcribe(File("unused")) }.isFailure)
    }

    @Test
    fun `authentication failure is not retried`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("sensitive"))
        val error = runCatching { client().transcribe(File("unused")) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("401"))
        assertFalse(error?.message.orEmpty().contains("sensitive"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `empty audio result fails visibly`() = runTest {
        server.enqueue(response(""))
        assertTrue(runCatching { client().transcribe(File("unused")) }.isFailure)
        assertTrue(runCatching { client(0).transcribe(File("unused")) }.isFailure)
    }

    @Test
    fun `Alibaba and DeepSeek require no Gemini key`() {
        assertNull(processingConfigurationError(true, "", SummaryProvider.DEEPSEEK, "deep", "model", TranscriptionProvider.ALIBABA, "ali", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3-asr-flash"))
        assertTrue(processingConfigurationError(true, "", SummaryProvider.DEEPSEEK, "deep", "model", TranscriptionProvider.ALIBABA, "", "https://example.com", "asr")?.contains("Alibaba") == true)
    }
}
