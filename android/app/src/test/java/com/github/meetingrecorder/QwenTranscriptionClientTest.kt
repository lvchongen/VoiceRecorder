package com.github.meetingrecorder

import com.github.meetingrecorder.data.AudioChunks
import com.github.meetingrecorder.data.PcmChunker
import com.github.meetingrecorder.data.QwenEngine
import com.github.meetingrecorder.data.QwenModelStore
import com.github.meetingrecorder.data.QwenTranscriptionClient
import com.github.meetingrecorder.data.SummaryProvider
import com.github.meetingrecorder.data.TranscriptionProvider
import com.github.meetingrecorder.data.processingConfigurationError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QwenTranscriptionClientTest {
    @get:Rule val folder = TemporaryFolder()
    private val store get() = QwenModelStore(folder.root, emptyList())
    private fun chunks(seconds: Int) = AudioChunks { _, consume ->
        val pcm = PcmChunker(16000, 1)
        pcm.add(ByteArray(seconds * 16000 * 2), consume)
        pcm.finish(consume)
    }

    @Test fun `long audio is bounded and preserves bilingual output and timestamps`() = runTest {
        val lengths = mutableListOf<Int>()
        var closed = false
        val client = QwenTranscriptionClient(store, chunks(45)) {
            object : QwenEngine {
                override fun transcribe(samples: FloatArray): String {
                    lengths += samples.size
                    return "中文 meeting ${lengths.size}"
                }
                override fun close() {
                    closed = true
                }
            }
        }
        val result = client.transcribe(folder.newFile())
        assertEquals(listOf(320000, 320000, 80000), lengths)
        assertTrue(result.contains("[00:00:20] 中文 meeting 2"))
        assertTrue(result.contains("[00:00:40] 中文 meeting 3"))
        assertTrue(closed)
    }

    @Test fun `decoder failure closes model and never returns partial transcript`() = runTest {
        var closed = false
        val client = QwenTranscriptionClient(store, chunks(30)) {
            object : QwenEngine {
                var calls = 0
                override fun transcribe(samples: FloatArray): String {
                    if (++calls == 2) throw CancellationException("cancelled")
                    return "first"
                }
                override fun close() {
                    closed = true
                }
            }
        }
        assertTrue(runCatching { client.transcribe(folder.newFile()) }.exceptionOrNull() is CancellationException)
        assertTrue(closed)
    }

    @Test fun `empty recognition is an error`() = runTest {
        val client = QwenTranscriptionClient(store, chunks(1)) {
            object : QwenEngine {
                override fun transcribe(samples: FloatArray) = ""
                override fun close() { }
            }
        }
        assertTrue(runCatching { client.transcribe(folder.newFile()) }.isFailure)
    }

    @Test fun `Qwen and DeepSeek require only summary key`() {
        assertNull(processingConfigurationError(true, "", SummaryProvider.DEEPSEEK, "key", "model", TranscriptionProvider.QWEN))
    }
}
