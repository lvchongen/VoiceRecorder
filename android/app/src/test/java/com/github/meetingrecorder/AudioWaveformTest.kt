package com.github.meetingrecorder

import com.github.meetingrecorder.data.AudioChunk
import com.github.meetingrecorder.ui.detail.WaveformEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioWaveformTest {
    @Test fun `silence stays silent and peaks follow chunk timing including negative full scale`() {
        val envelope = WaveformEnvelope(2000, 2)
        envelope.add(chunk(0, shortArrayOf(0, 0)))
        envelope.add(chunk(1000, shortArrayOf(Short.MIN_VALUE, 0)))
        assertEquals(listOf(0f, 1f), envelope.values())
    }

    @Test fun `stereo uses both channels and malformed short data does not invent peaks`() {
        val envelope = WaveformEnvelope(1000, 1)
        envelope.add(AudioChunk(byteArrayOf(1), 0))
        assertEquals(listOf(0f), envelope.values())
        envelope.add(chunk(0, shortArrayOf(0, 16000), channels = 2))
        assertTrue(envelope.values().single() > 0.99f)
    }

    private fun chunk(start: Long, samples: ShortArray, channels: Int = 1): AudioChunk {
        val buffer = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(22, channels.toShort())
        buffer.putInt(24, 8000)
        buffer.position(44)
        samples.forEach { buffer.putShort(it) }
        return AudioChunk(buffer.array(), start / 1000, start)
    }
}
