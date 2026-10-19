package com.github.meetingrecorder

import com.github.meetingrecorder.data.AudioChunk
import com.github.meetingrecorder.data.PcmChunker
import com.github.meetingrecorder.data.SummaryProvider
import com.github.meetingrecorder.data.TranscriptionProvider
import com.github.meetingrecorder.data.WhisperModel
import com.github.meetingrecorder.data.processingConfigurationError
import com.github.meetingrecorder.data.whisperSamples
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WhisperAudioTest {
    @Test
    fun `stereo 48k audio becomes mono 16k with preserved level and duration`() = runTest {
        val pcm = ByteBuffer.allocate(48000 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(48000) {
            pcm.putShort(16384)
            pcm.putShort(0)
        }
        var chunk: AudioChunk? = null
        val chunker = PcmChunker(48000, 2)
        chunker.add(pcm.array()) { chunk = it }
        chunker.finish { chunk = it }
        val output = whisperSamples(requireNotNull(chunk).wav)
        assertEquals(16000, output.size)
        assertTrue(output.all { kotlin.math.abs(it - 0.25f) < 0.0001f })
    }

    @Test
    fun `all model choices have distinct files and checksums and medium is default fallback`() {
        assertEquals(6, WhisperModel.all.size)
        assertEquals(6, WhisperModel.all.map { it.filename }.toSet().size)
        assertTrue(WhisperModel.all.all { it.sha256.matches(Regex("[0-9a-f]{64}")) && it.bytes > 0 })
        assertEquals("medium", WhisperModel.selected("unknown").id)
    }

    @Test
    fun `local transcription plus DeepSeek needs only the summary key`() {
        assertNull(processingConfigurationError(true, "", SummaryProvider.DEEPSEEK, "deep", "model", TranscriptionProvider.WHISPER))
    }
}
