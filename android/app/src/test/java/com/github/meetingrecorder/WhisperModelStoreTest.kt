package com.github.meetingrecorder

import com.github.meetingrecorder.data.WhisperModel
import com.github.meetingrecorder.data.WhisperModelStore
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class WhisperModelStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val server = MockWebServer()

    @After fun teardown() {
        server.shutdown()
    }

    @Test
    fun `verified download installs model and delete removes it`() = runTest {
        server.start()
        val bytes = "synthetic model fixture"
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
        val model = WhisperModel("test", "test.bin", bytes.length.toLong(), hash)
        val store = WhisperModelStore(folder.root) { server.url("/model").toString() }
        server.enqueue(MockResponse().setBody(bytes))
        store.download(model) { }
        assertTrue(store.installed(model))
        assertTrue(store.withModel(model) { it.readText() == bytes })
        store.delete(model)
        assertFalse(store.installed(model))
    }

    @Test
    fun `checksum failure never installs partial model`() = runTest {
        server.start()
        val model = WhisperModel("test", "test.bin", 3, "0".repeat(64))
        val store = WhisperModelStore(folder.root) { server.url("/model").toString() }
        server.enqueue(MockResponse().setBody("bad"))
        assertTrue(runCatching { store.download(model) { } }.isFailure)
        assertFalse(store.installed(model))
        assertTrue(folder.root.listFiles().orEmpty().isEmpty())
    }
}
