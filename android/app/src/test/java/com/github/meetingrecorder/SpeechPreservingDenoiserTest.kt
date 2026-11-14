package com.github.meetingrecorder

import com.github.meetingrecorder.audio.SpeechPreservingDenoiser
import com.github.meetingrecorder.audio.StreamingDenoiser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import kotlin.math.abs

class SpeechPreservingDenoiserTest {
    /** Mirrors model delay plus STFT buffering; output boundaries differ from input boundaries. */
    private class DelayedWet(private val gain: Float) : StreamingDenoiser {
        private val history = ArrayDeque<Short>().apply { repeat(640) { add(0) } }
        private val buffered = ArrayDeque<Short>()
        var closed = false
        override fun process(samples: ShortArray): ShortArray {
            samples.forEach {
                history.addLast(it)
                buffered.addLast((history.removeFirst() * gain).toInt().toShort())
            }
            val count = ((buffered.size - 160).coerceAtLeast(0) / 160) * 160
            return ShortArray(count) { buffered.removeFirst() }
        }
        override fun flush() = ShortArray(buffered.size) { buffered.removeFirst() }
        override fun close() {
            closed = true
        }
    }

    private fun render(input: ShortArray, engine: StreamingDenoiser): ShortArray {
        val output = mutableListOf<Short>()
        engine.use {
            var offset = 0
            val sizes = intArrayOf(1, 7, 320, 501, 19)
            var frame = 0
            while (offset < input.size) {
                val end = minOf(input.size, offset + sizes[frame++ % sizes.size])
                output.addAll(it.process(input.copyOfRange(offset, end)).toList())
                offset = end
            }
            output.addAll(it.flush().toList())
        }
        return output.toShortArray()
    }

    @Test fun `delayed speech aligns without echoes across arbitrary frame boundaries`() {
        val input = ShortArray(8031) { ((it * 7919 % 30001) - 15000).toShort() }
        val wet = DelayedWet(1f)
        val actual = render(input, SpeechPreservingDenoiser(wet))
        assertEquals(input.size, actual.size)
        assertTrue(input.indices.all { abs(input[it] - actual[it]) <= 1 })
        assertTrue(wet.closed)
    }

    @Test fun `over suppressed speech retains half amplitude instead of disappearing`() {
        val input = ShortArray(3207) { if (it % 2 == 0) 1000 else -1000 }
        val actual = render(input, SpeechPreservingDenoiser(DelayedWet(0f)))
        assertEquals(input.size, actual.size)
        assertTrue(actual.indices.all { abs(actual[it].toInt()) in 500..502 })
        assertTrue(actual.indices.all { actual[it] * input[it] > 0 })
    }

    @Test fun `sub frame recordings and empty input drain without losing tail`() {
        for (size in listOf(0, 1, 159, 160, 161, 639, 640, 641)) {
            val input = ShortArray(size) { 1234 }
            val actual = render(input, SpeechPreservingDenoiser(DelayedWet(1f)))
            assertEquals(size, actual.size)
            assertTrue(actual.all { abs(it - 1234) <= 1 })
        }
    }

    @Test(expected = IllegalStateException::class)
    fun `stalled native model fails promptly instead of buffering whole meeting`() {
        val broken = object : StreamingDenoiser {
            override fun process(samples: ShortArray) = shortArrayOf()
            override fun flush() = shortArrayOf()
            override fun close() {}
        }
        SpeechPreservingDenoiser(broken).use { model ->
            repeat(10) { model.process(ShortArray(320)) }
        }
    }
}
