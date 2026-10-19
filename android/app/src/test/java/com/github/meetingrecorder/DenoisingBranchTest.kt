package com.github.meetingrecorder

import com.github.meetingrecorder.audio.DenoisingBranch
import com.github.meetingrecorder.audio.PcmSink
import com.github.meetingrecorder.audio.StreamingDenoiser
import com.github.meetingrecorder.audio.preferredRecording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DenoisingBranchTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Sink : PcmSink {
        val samples = mutableListOf<Short>()
        var closed = false
        override fun write(samples: ShortArray) {
            this.samples.addAll(samples.toList())
        }
        override fun close() {
            closed = true
        }
    }

    @Test fun `flush preserves final partial frame and input buffer is copied`() {
        val sink = Sink()
        var pending = shortArrayOf()
        var released = false
        val gate = CountDownLatch(1)
        val engine = object : StreamingDenoiser {
            override fun process(samples: ShortArray): ShortArray {
                check(gate.await(3, TimeUnit.SECONDS))
                val previous = pending
                pending = samples
                return previous
            }
            override fun flush() = pending
            override fun close() {
                released = true
            }
        }
        val branch = DenoisingBranch(engine, sink, { fail(it) })
        val input = shortArrayOf(1, 2, 3)
        branch.accept(input)
        input.fill(99)
        branch.accept(shortArrayOf(4))
        gate.countDown()
        assertTrue(branch.finish())
        assertEquals(listOf<Short>(1, 2, 3, 4), sink.samples)
        assertEquals(4L, branch.inputSamples)
        assertEquals(branch.inputSamples, branch.outputSamples)
        assertTrue(released && sink.closed)
    }

    @Test fun `inference failure closes resources and permits subsequent raw frames`() {
        val sink = Sink()
        var released = false
        val engine = object : StreamingDenoiser {
            override fun process(samples: ShortArray): ShortArray = error("model failure")
            override fun flush(): ShortArray = error("must not flush after failure")
            override fun close() {
                released = true
            }
        }
        val branch = DenoisingBranch(engine, sink, {})
        branch.accept(shortArrayOf(1))
        assertFalse(branch.finish())
        assertEquals("model failure", branch.failure)
        assertTrue(released && sink.closed)
    }

    @Test fun `queue saturation disables denoising instead of blocking capture`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sink = Sink()
        val engine = object : StreamingDenoiser {
            override fun process(samples: ShortArray): ShortArray {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                return samples
            }
            override fun flush() = shortArrayOf()
            override fun close() {}
        }
        val branch = DenoisingBranch(engine, sink, {}, capacity = 1)
        try {
            branch.accept(shortArrayOf(1))
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            branch.accept(shortArrayOf(2))
            branch.accept(shortArrayOf(3))
            assertNotNull(branch.failure)
            branch.accept(shortArrayOf(4)) // capture remains usable after branch failure
        } finally {
            release.countDown()
        }
        assertFalse(branch.finish())
        assertTrue(sink.closed)
    }

    @Test fun `lost samples reject enhanced output`() {
        val sink = Sink()
        val engine = object : StreamingDenoiser {
            override fun process(samples: ShortArray) = shortArrayOf()
            override fun flush() = shortArrayOf()
            override fun close() {}
        }
        val branch = DenoisingBranch(engine, sink, {})
        branch.accept(shortArrayOf(1, 2))
        assertFalse(branch.finish())
    }

    @Test fun `partial enhanced file is never selected and original remains available`() {
        val dir = temporary.newFolder()
        val original = File(dir, "recording.m4a").apply { writeText("raw") }
        File(dir, "recording-denoised.m4a.partial").writeText("partial")
        assertEquals(original, preferredRecording(dir))
        val enhanced = File(dir, "recording-denoised.m4a").apply { writeText("complete") }
        assertEquals(enhanced, preferredRecording(dir))
        enhanced.writeText("")
        assertEquals(original, preferredRecording(dir))
    }
}
