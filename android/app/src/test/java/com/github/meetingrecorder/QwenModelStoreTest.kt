package com.github.meetingrecorder

import com.github.meetingrecorder.data.QwenModelStore
import com.github.meetingrecorder.data.WhisperModel
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class QwenModelStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val server = MockWebServer()

    @After fun teardown() = server.shutdown()
    private fun asset(path: String, content: String) = WhisperModel(
        "test",
        path,
        content.length.toLong(),
        MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) },
    )

    @Test fun `failed package is unavailable and retry reuses verified files`() = runTest {
        server.start()
        val assets = listOf(asset("encoder.onnx", "encoder"), asset("tokenizer/vocab.json", "vocab"))
        val store = QwenModelStore(folder.root, assets) { server.url("/${it.filename}").toString() }
        server.enqueue(MockResponse().setBody("encoder"))
        server.enqueue(MockResponse().setBody("wrong"))
        assertTrue(runCatching { store.download {} }.isFailure)
        assertFalse(store.installed())
        assertTrue(runCatching { store.withModel { } }.isFailure)
        assertFalse(folder.root.resolve("tokenizer/vocab.json.part").exists())
        server.enqueue(MockResponse().setBody("vocab"))
        var percent = 0
        store.download { percent = it }
        assertTrue(store.installed())
        assertEquals(100, percent)
        assertEquals(3, server.requestCount)
        store.withModel { assertEquals("vocab", it.resolve("tokenizer/vocab.json").readText()) }
        store.delete()
        assertFalse(store.installed())
    }
}
