package com.github.meetingrecorder

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.meetingrecorder.data.Config
import com.github.meetingrecorder.speakers.SpeakerModelStore
import com.github.meetingrecorder.speakers.SpeakerRepository
import com.github.meetingrecorder.speakers.SpeakerService
import com.github.meetingrecorder.speakers.SpeakerViewModel
import com.github.meetingrecorder.speakers.voiceCandidates
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit opt-in, staged public fixture only: never records the microphone or edits user meetings. */
@RunWith(AndroidJUnit4::class)
class SpeakerDeviceTest {
    @Test fun localDiarizationAndConfirmedEnrollment(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("speakerTest") == "true")
        val root = File(instrumentation.targetContext.cacheDir, "speaker-device-test")
        val models = SpeakerModelStore(File(root, "models"))
        assertTrue("Stage the pinned model files before running this opt-in test", models.installed())
        val directory = File(root, "session-${System.currentTimeMillis()}").apply { mkdirs() }
        val audio = File(directory, "recording.wav")
        File(root, "two-speakers.wav").copyTo(audio)
        val notes = File(directory, "notes.md").apply { writeText("Keep existing notes") }
        val repository = SpeakerRepository(File(directory, "private"))
        val service = SpeakerService(models, repository)
        val started = System.nanoTime()
        try {
            val result = service.analyze(audio, directory)
            val report = "speakers=${result.speakers.map { it.label }}\nturns=${result.turns.size}\nsamples=${result.speakers.map { it.samples.size }}\nelapsedMs=${(System.nanoTime() - started) / 1000000}\n"
            File(root, "report.txt").writeText(report)
            assertTrue("Expected two speakers in the public fixture: $report", result.speakers.size >= 2)
            assertEquals("A", result.speakers.first().label)
            assertTrue(result.turns.all { it.startMs >= 0 && it.endMs > it.startMs })
            assertTrue(repository.profiles().isEmpty())
            val speaker = result.speakers.first { it.samples.isNotEmpty() }
            repository.confirm(directory, speaker.label, "Fixture person", saveVoiceprint = true, sampleIds = setOf(speaker.samples.first().id))
            val profile = repository.profiles().single()
            assertEquals(1, profile.samples.size)
            val candidate = voiceCandidates(listOf(speaker.samples.first()), repository.profiles()).single()
            assertTrue(candidate.similarity > 0.99f)
            assertTrue(repository.profileSample(profile, profile.samples.single()).length() > 44)
            if (InstrumentationRegistry.getArguments().getString("speakerPlayback") == "true") {
                val viewModel = SpeakerViewModel(service, repository, Config(instrumentation.targetContext))
                try {
                    instrumentation.runOnMainSync { viewModel.load(directory.absolutePath) }
                    val loaded = withTimeout(5000) { viewModel.state.first { !it.busy && (it.profiles.isNotEmpty() || it.error != null) } }
                    assertNull(loaded.error)
                    instrumentation.runOnMainSync { viewModel.playProfileSample(profile.id, profile.samples.single().id) }
                    withTimeout(5000) { viewModel.state.first { it.playingSample != null || it.error != null } }
                    val completed = withTimeout(10000) { viewModel.state.first { it.playingSample == null } }
                    assertNull(completed.error)
                    File(root, "report.txt").appendText("samplePlayback=completed\n")
                } finally {
                    instrumentation.runOnMainSync {
                        viewModel.stopSample()
                        viewModel.cancel()
                    }
                }
            }
            repository.deleteProfile(profile.id)
            assertTrue(repository.profiles().isEmpty())
            assertEquals("Keep existing notes", notes.readText())
            assertTrue(audio.exists())
            assertFalse(File(directory, "speakers.json").readText().contains("embedding"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
