package com.github.meetingrecorder

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.meetingrecorder.data.AndroidAudioChunks
import com.github.meetingrecorder.data.QwenModelStore
import com.github.meetingrecorder.data.QwenTranscriptionClient
import com.github.meetingrecorder.data.whisperSamples
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sqrt

/** Read-only diagnosis explicitly supplied with a local audio path; never saves transcript text. */
@RunWith(AndroidJUnit4::class)
class TranscriptionDiagnosticTest {
    @Test fun recognizeCompleteRecording(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val path = InstrumentationRegistry.getArguments().getString("diagnosticAudio")
        assumeTrue(path != null)
        val context = instrumentation.targetContext
        val client = QwenTranscriptionClient(QwenModelStore(File(context.noBackupFilesDir, "qwen3-asr-model")))
        val report = File(context.cacheDir, "transcription-diagnostic.txt")
        report.writeText("")
        val original = File(requireNotNull(path))
        for (audio in listOf(original, File(original.parentFile, "recording-denoised.m4a")).filter { it.isFile }) {
            var size = 0L
            var sum = 0.0
            AndroidAudioChunks().read(audio) { chunk ->
                val pcm = whisperSamples(chunk.wav)
                size += pcm.size
                pcm.forEach { sum += it.toDouble() * it }
            }
            val started = System.nanoTime()
            val result = runCatching { client.transcribe(audio) }
            val line = "${audio.name}: seconds=${size / 16000.0}, rms=${sqrt(sum / size.coerceAtLeast(1))}, chars=${result.getOrNull()?.length ?: 0}, error=${result.exceptionOrNull()?.javaClass?.simpleName}, elapsedMs=${(System.nanoTime() - started) / 1000000}\n"
            report.appendText(line)
            println(line)
        }
        if (InstrumentationRegistry.getArguments().getString("diagnosticSpeakers") == "true") {
            val directory = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "voice-repro-").toFile()
            try {
                val source = File(original.parentFile, "recording-denoised.m4a").takeIf { it.isFile } ?: original
                val audio = File(directory, "recording.m4a")
                source.copyTo(audio)
                val service = com.github.meetingrecorder.speakers.SpeakerService(
                    com.github.meetingrecorder.speakers.SpeakerModelStore(File(context.noBackupFilesDir, "speaker-models")),
                    com.github.meetingrecorder.speakers.SpeakerRepository(File(directory, "private")),
                )
                var retried = false
                val result = runCatching {
                    com.github.meetingrecorder.speakers.SpeakerTranscriptionClient(service, client).transcribe(audio, "audio/mp4") {
                        if (it.contains("完整录音")) retried = true
                    }
                }
                report.appendText("speakerPipeline: chars=${result.getOrNull()?.length ?: 0}, error=${result.exceptionOrNull()?.javaClass?.simpleName}, fullAudioRetry=$retried\n")
                if (InstrumentationRegistry.getArguments().getString("expectSuccess") == "true") {
                    org.junit.Assert.assertTrue("Speaker processing must preserve recognizable speech", result.getOrNull()?.isNotBlank() == true)
                }
            } finally {
                directory.deleteRecursively()
            }
        }
    }
}
