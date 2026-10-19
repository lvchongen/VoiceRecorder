package com.github.meetingrecorder

import com.github.meetingrecorder.data.AudioChunk
import com.github.meetingrecorder.data.PcmChunker
import com.github.meetingrecorder.data.summaryLanguageInstruction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmChunkerTest {
    @Test
    fun `arbitrary decoder buffers preserve every sample across bounded chunks`() = runTest {
        val pcm = ByteArray(70_004) { (it % 127).toByte() }
        val chunks = mutableListOf<AudioChunk>()
        val chunker = PcmChunker(16000, 2, 32000)
        pcm.asList().chunked(703).forEach { chunker.add(it.toByteArray()) { chunk -> chunks += chunk } }
        chunker.finish { chunks += it }
        assertEquals(3, chunks.size)
        assertTrue(chunks.all { it.wav.size <= 32044 })
        assertArrayEquals(pcm, chunks.flatMap { it.wav.drop(44) }.toByteArray())
        val header = ByteBuffer.wrap(chunks.first().wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16000, header.getInt(24))
        assertEquals(2, header.getShort(22).toInt())
        assertEquals(32000, header.getInt(40))
    }

    @Test
    fun `chunk timestamps reflect decoded duration and final short segment`() = runTest {
        val chunks = mutableListOf<AudioChunk>()
        val chunker = PcmChunker(8000, 1, 16000)
        chunker.add(ByteArray(40000)) { chunks += it }
        chunker.finish { chunks += it }
        assertEquals(listOf(0L, 1L, 2L), chunks.map { it.startSeconds })
    }

    @Test
    fun `bilingual output policy requires matching facts while original mode is explicit`() {
        assertTrue(summaryLanguageInstruction("Bilingual").contains("exactly the same facts"))
        assertTrue(summaryLanguageInstruction("Original").contains("source language"))
        assertTrue(summaryLanguageInstruction("Chinese").contains("Simplified Chinese"))
    }
}
