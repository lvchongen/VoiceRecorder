package com.github.meetingrecorder

import com.github.meetingrecorder.data.AudioChunk
import com.github.meetingrecorder.data.AudioChunks
import com.github.meetingrecorder.data.TranscriptionClient
import com.github.meetingrecorder.speakers.MeetingSpeaker
import com.github.meetingrecorder.speakers.SpeakerEngine
import com.github.meetingrecorder.speakers.SpeakerModelStore
import com.github.meetingrecorder.speakers.SpeakerRepository
import com.github.meetingrecorder.speakers.SpeakerService
import com.github.meetingrecorder.speakers.SpeakerTurn
import com.github.meetingrecorder.speakers.pcmWave
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SpeakerServiceTest {
    @get:Rule val temp = TemporaryFolder()
    private val pcm = FloatArray(8 * 16000) { if (it % 2 == 0) 0.1f else -0.1f }

    private class FakeEngine(private val fail: Boolean = false) : SpeakerEngine {
        var closed = false
        override fun segments(samples: FloatArray): List<SpeakerTurn> {
            if (fail) error("native failure")
            return listOf(SpeakerTurn(0, 4000, "7"), SpeakerTurn(4000, 8000, "3"))
        }
        var embeddingCalls = 0
        override fun embedding(samples: FloatArray): List<Float> = if (embeddingCalls++ % 2 == 0) listOf(1f, 0f) else listOf(0f, 1f)
        override fun close() {
            closed = true
        }
    }

    @Test fun `silent speaker clip does not abort an otherwise recognizable recording`() = runTest {
        val repo = SpeakerRepository(temp.newFolder())
        val dir = temp.newFolder()
        val audio = File(dir, "recording.wav").apply { writeBytes(pcmWave(pcm)) }
        val chunks = AudioChunks { file, consume -> consume(AudioChunk(file.readBytes(), 0)) }
        val service = SpeakerService(SpeakerModelStore(temp.newFolder(), assets = emptyList()), repo, chunks) { _, _ -> FakeEngine() }
        val qwen = com.github.meetingrecorder.data.QwenTranscriptionClient(
            com.github.meetingrecorder.data.QwenModelStore(temp.newFolder(), emptyList()),
            chunks,
        ) {
            object : com.github.meetingrecorder.data.QwenEngine {
                override fun transcribe(samples: FloatArray) = if (samples.size < pcm.size) "" else "整段录音可以识别"
                override fun close() { }
            }
        }
        val result = com.github.meetingrecorder.speakers.SpeakerTranscriptionClient(service, qwen).transcribe(audio)
        assertTrue(result.contains("整段录音可以识别"))
    }

    @Test fun `speaker retry never hides cancellation or authentication errors`() = runTest {
        val dir = temp.newFolder()
        val audio = File(dir, "recording.wav").apply { writeBytes(pcmWave(pcm)) }
        val repo = SpeakerRepository(temp.newFolder())
        val chunks = AudioChunks { file, consume -> consume(AudioChunk(file.readBytes(), 0)) }
        val service = SpeakerService(SpeakerModelStore(temp.newFolder(), assets = emptyList()), repo, chunks) { _, _ -> FakeEngine() }
        for (failure in listOf(IllegalStateException("authentication failed"), kotlinx.coroutines.CancellationException("cancelled"))) {
            var calls = 0
            val delegate = object : TranscriptionClient {
                override suspend fun transcribe(audioFile: File, mimeType: String, onStatus: (String) -> Unit): String {
                    calls++
                    throw failure
                }
            }
            val result = runCatching { com.github.meetingrecorder.speakers.SpeakerTranscriptionClient(service, delegate).transcribe(audio) }
            assertEquals(failure.javaClass, result.exceptionOrNull()?.javaClass)
            assertEquals(failure.message, result.exceptionOrNull()?.message)
            assertEquals(1, calls)
        }
    }

    @Test fun `windows stitch meeting identities with precise offsets and cache does not reanalyze`() = runTest {
        val repo = SpeakerRepository(temp.newFolder())
        val dir = temp.newFolder()
        val audio = File(dir, "recording.m4a").apply { writeText("fixture") }
        val engine = FakeEngine()
        val chunks = AudioChunks { _, consume ->
            consume(AudioChunk(pcmWave(pcm), 0, 0))
            consume(AudioChunk(pcmWave(pcm), 8, 8500))
        }
        var creations = 0
        val service = SpeakerService(SpeakerModelStore(temp.newFolder(), assets = emptyList()), repo, chunks) { _, _ ->
            creations++
            engine
        }
        val result = service.analyze(audio, dir)
        assertEquals(listOf("A", "B"), result.speakers.map(MeetingSpeaker::label))
        assertEquals(listOf("A", "B", "A", "B"), result.turns.map { it.label })
        assertEquals(8500, result.turns[2].startMs)
        assertTrue(engine.closed)
        repo.confirm(dir, "A", "张三")
        assertEquals("张三", service.analyze(audio, dir).speakers.first().name)
        assertEquals(1, creations)
        assertTrue(repo.profiles().isEmpty())
    }

    @Test fun `native failure releases resources and does not replace saved identities`() = runTest {
        val repo = SpeakerRepository(temp.newFolder())
        val dir = temp.newFolder()
        val audio = File(dir, "recording.m4a").apply { writeText("fixture") }
        val model = SpeakerModelStore(temp.newFolder(), assets = emptyList())
        val chunks = AudioChunks { _, consume -> consume(AudioChunk(pcmWave(pcm), 0)) }
        val good = SpeakerService(model, repo, chunks) { _, _ -> FakeEngine() }
        val previous = good.analyze(audio, dir)
        audio.appendText("changed")
        val engine = FakeEngine(fail = true)
        val failing = SpeakerService(model, repo, chunks) { _, _ -> engine }
        assertTrue(runCatching { failing.analyze(audio, dir) }.isFailure)
        assertEquals(previous.id, repo.readMeeting(dir)!!.id)
        assertTrue(engine.closed)
    }

    @Test fun `turn transcription uses global labels and cleans temporary audio on failure`() = runTest {
        val repo = SpeakerRepository(temp.newFolder())
        val dir = temp.newFolder()
        val audio = File(dir, "recording.m4a").apply { writeText("fixture") }
        val chunks = AudioChunks { _, consume -> consume(AudioChunk(pcmWave(pcm), 0)) }
        val service = SpeakerService(SpeakerModelStore(temp.newFolder(), assets = emptyList()), repo, chunks) { _, _ -> FakeEngine() }
        var calls = 0
        val delegate = object : TranscriptionClient {
            override suspend fun transcribe(audioFile: File, mimeType: String, onStatus: (String) -> Unit): String {
                assertEquals("audio/wav", mimeType)
                assertTrue(audioFile.isFile)
                calls++
                return "[00:00:00] **Person 1:** turn $calls"
            }
        }
        val result = service.transcribe(audio, dir, delegate) {}
        assertTrue(result.contains("[00:00:00] **A:** turn 1"))
        assertTrue(result.contains("[00:00:04] **B:** turn 2"))
        assertFalse(result.contains("Person 1"))
        val state = repo.readMeeting(dir)!!
        assertFalse(repo.analysisDirectory(state.id).listFiles()!!.any { it.name.startsWith("speaker-turn-") })
        val failing = object : TranscriptionClient {
            override suspend fun transcribe(audioFile: File, mimeType: String, onStatus: (String) -> Unit): String = error("ASR failed")
        }
        assertTrue(runCatching { service.transcribe(audio, dir, failing) {} }.isFailure)
        assertFalse(repo.analysisDirectory(state.id).listFiles()!!.any { it.name.startsWith("speaker-turn-") })
    }
}
