package com.github.meetingrecorder

import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.meetingrecorder.audio.RecordingPhase
import com.github.meetingrecorder.audio.RecordingService
import com.github.meetingrecorder.data.AndroidAudioChunks
import com.github.meetingrecorder.data.whisperSamples
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Explicit opt-in only: records microphone for 20 seconds, with screen off for the final 15. */
@RunWith(AndroidJUnit4::class)
class MicrophoneDenoisingDeviceTest {
    @Test fun recordWhileScreenOff(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("microphoneTest") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as MeetingRecorderApp
        val config = app.container.config
        val originalSetting = config.recordingDenoiseEnabled
        val originalDnd = config.dndDuringRecordingEnabled
        val dir = File(context.cacheDir, "denoise-mic-test-${System.currentTimeMillis()}").apply { mkdirs() }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        try {
            config.recordingDenoiseEnabled = true
            config.dndDuringRecordingEnabled = false
            RecordingService.start(context, dir, 64000)
            val started = withTimeout(15000) {
                RecordingService.status.first { it.phase == RecordingPhase.RECORDING || it.phase == RecordingPhase.FAILED }
            }
            assertEquals(RecordingPhase.RECORDING, started.phase)
            delay(5000)
            instrumentation.uiAutomation.executeShellCommand("input keyevent 223").close()
            delay(15000)
            RecordingService.stop(context)
            val stopped = withTimeout(15000) {
                RecordingService.status.first { it.phase == RecordingPhase.STOPPED || it.phase == RecordingPhase.FAILED }
            }
            assertEquals(RecordingPhase.STOPPED, stopped.phase)
            assertFalse("Microphone was silenced", stopped.silenced)
            assertEquals(File(dir, "recording-denoised.m4a").absolutePath, stopped.outputPath)
            assertFalse("Denoising fallback warning", File(dir, "denoise-warning.txt").exists())
            val report = StringBuilder("screenOffSeconds=15\n")
            val durations = mutableListOf<Double>()
            for (name in listOf("recording.m4a", "recording-denoised.m4a")) {
                val file = File(dir, name)
                assertTrue(file.length() > 1000)
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    val format = extractor.getTrackFormat(0)
                    val seconds = format.getLong(MediaFormat.KEY_DURATION) / 1e6
                    durations += seconds
                    assertTrue("Unexpected duration: $seconds", seconds in 19.0..23.0)
                    assertEquals(16000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    report.append("$name bytes=${file.length()} seconds=$seconds\n")
                } finally {
                    extractor.release()
                }
                var samples = 0L
                var energy = 0.0
                var peak = 0.0
                AndroidAudioChunks().read(file) { chunk ->
                    val pcm = whisperSamples(chunk.wav)
                    samples += pcm.size
                    pcm.forEach {
                        energy += it.toDouble() * it
                        peak = maxOf(peak, abs(it.toDouble()))
                    }
                }
                assertTrue("No captured audio", energy > 0.0001)
                report.append("decodedSamples=$samples meanSquare=${energy / samples} peak=$peak\n")
            }
            assertTrue(abs(durations[0] - durations[1]) < 0.1)
            File(dir, "results.txt").writeText(report.toString())
            File(context.cacheDir, "denoise-microphone-test-path.txt").writeText(dir.name)
            android.util.Log.i("DenoiseDeviceTest", report.toString())
        } finally {
            if (RecordingService.status.value.phase == RecordingPhase.RECORDING) {
                RecordingService.stop(context)
                withTimeout(15000) { RecordingService.status.first { it.phase != RecordingPhase.RECORDING } }
            }
            config.recordingDenoiseEnabled = originalSetting
            config.dndDuringRecordingEnabled = originalDnd
            instrumentation.uiAutomation.executeShellCommand("input keyevent 224").close()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
