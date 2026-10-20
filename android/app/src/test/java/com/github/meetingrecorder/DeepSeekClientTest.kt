package com.github.meetingrecorder

import com.github.meetingrecorder.data.Config
import com.github.meetingrecorder.data.DeepSeekClient
import com.github.meetingrecorder.data.SummaryProvider
import com.github.meetingrecorder.data.processingConfigurationError
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class DeepSeekClientTest {
    private val server = MockWebServer()
    private val config = mock<Config>()
    private val delays = mutableListOf<Long>()
    private lateinit var client: DeepSeekClient

    @Before
    fun setup() {
        server.start()
        whenever(config.deepSeekApiKey).thenReturn("test-deepseek-key")
        whenever(config.deepSeekModel).thenReturn("custom-model")
        whenever(config.summarizationPrompt).thenReturn("总结：{transcript}")
        whenever(config.titlePrompt).thenReturn("标题：{notes}")
        client = DeepSeekClient(config, server.url("/").toString(), delayFn = { delays += it })
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private fun result(content: String = "会议摘要", finish: String = "stop") = MockResponse().setBody(
        JSONObject().put(
            "choices",
            org.json.JSONArray().put(
                JSONObject()
                    .put("finish_reason", finish)
                    .put("message", JSONObject().put("content", content).put("reasoning_content", "private reasoning")),
            ),
        ).toString(),
    )

    @Test
    fun `sends text with selected model and separate authorization`() = runTest {
        server.enqueue(result())
        assertEquals("会议摘要", client.summarize("张三说：\"周五交付\"\n下一行"))
        val request = server.takeRequest()
        assertEquals("/chat/completions", request.path)
        assertEquals("Bearer test-deepseek-key", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("custom-model", body.getString("model"))
        assertFalse(body.getBoolean("stream"))
        assertTrue(body.getJSONArray("messages").getJSONObject(1).getString("content").endsWith("总结：[P1] 张三说：\"周五交付\"\n[P2] 下一行"))
        assertFalse(body.toString().contains("inline_data"))
    }

    @Test
    fun `explicit options reach DeepSeek and invalid evidence is rejected`() = runTest {
        server.enqueue(result("Proposal [P1]"))
        val options = com.github.meetingrecorder.data.SummaryOptions(
            com.github.meetingrecorder.data.MeetingTemplate.BRAINSTORM,
            com.github.meetingrecorder.data.SummaryDetail.QUICK,
            "Original",
        )
        client.summarizeWithOptions("A: propose B", options)
        val prompt = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages").getJSONObject(1).getString("content")
        assertTrue(prompt.contains("头脑风暴"))
        assertTrue(prompt.contains("速览"))
        assertTrue(prompt.contains("source language"))
        server.enqueue(result("Action [P2]"))
        assertTrue(runCatching { client.summarizeWithOptions("one line", options) }.isFailure)
    }

    @Test
    fun `title uses title template and notes`() = runTest {
        server.enqueue(result("  项目周会  "))
        assertEquals("项目周会", client.generateTitle("确认上线"))
        assertEquals("标题：确认上线", JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages").getJSONObject(1).getString("content"))
    }

    @Test
    fun `transient failures retry with bounded backoff`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(result())
        assertEquals("会议摘要", client.summarize("会议"))
        assertEquals(listOf(2000L, 4000L), delays)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `authentication failure is not retried and does not expose response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("secret-server-body"))
        val error = runCatching { client.summarize("会议") }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("authentication"))
        assertFalse(error?.message.orEmpty().contains("secret-server-body"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `truncated output is rejected instead of saved as complete`() = runTest {
        server.enqueue(result("partial", "length"))
        assertTrue(runCatching { client.summarize("会议") }.exceptionOrNull()?.message.orEmpty().contains("complete"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `reasoning without content is rejected`() = runTest {
        server.enqueue(result(""))
        assertTrue(runCatching { client.summarize("会议") }.exceptionOrNull()?.message.orEmpty().contains("empty"))
    }

    @Test
    fun `missing key and empty transcript fail before network`() = runTest {
        assertTrue(runCatching { client.summarize(" ") }.isFailure)
        whenever(config.deepSeekApiKey).thenReturn("")
        assertTrue(runCatching { client.summarize("会议") }.isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `deepseek notes only needs no Gemini key but audio still does`() {
        assertNull(processingConfigurationError(false, "", SummaryProvider.DEEPSEEK, "key", "model"))
        assertNotNull(processingConfigurationError(true, "", SummaryProvider.DEEPSEEK, "key", "model"))
        assertNotNull(processingConfigurationError(false, "", SummaryProvider.GEMINI, "key", "model"))
        assertNotNull(processingConfigurationError(false, "gemini", SummaryProvider.DEEPSEEK, "", "model"))
        assertNull(processingConfigurationError(true, "gemini", SummaryProvider.GEMINI, "", ""))
    }
}
