package com.github.meetingrecorder

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.meetingrecorder.audio.AacPcmSink
import com.github.meetingrecorder.audio.GtcrnDenoiser
import com.github.meetingrecorder.data.AndroidAudioChunks
import com.github.meetingrecorder.data.whisperSamples
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Runs the shipped JNI/model and Android AAC encode/decode; no microphone or API key needed. */
@RunWith(AndroidJUnit4::class)
class RealtimeDenoisingDeviceTest {
    @SuppressLint("MissingPermission")
    @Test
    fun reportSystemNoiseSuppression() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = StringBuilder("NoiseSuppressor.isAvailable=${NoiseSuppressor.isAvailable()}\n")
        for (source in listOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION)) {
            val recorder = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(64000)
                .build()
            try {
                val ns = NoiseSuppressor.create(recorder.audioSessionId)
                try {
                    report.append("source=$source available=${ns != null} defaultEnabled=${ns?.enabled} control=${ns?.hasControl()} implementation=${ns?.descriptor?.name} vendor=${ns?.descriptor?.implementor}\n")
                    if (ns != null && ns.hasControl()) {
                        val original = ns.enabled
                        try {
                            val result = ns.setEnabled(true)
                            report.append("enableResult=$result enabled=${ns.enabled}\n")
                        } finally {
                            ns.enabled = original
                        }
                    }
                } finally {
                    ns?.release()
                }
            } finally {
                recorder.release()
            }
        }
        File(context.cacheDir, "denoise-system-probe.txt").writeText(report.toString())
        android.util.Log.i("DenoiseDeviceTest", report.toString())
    }

    @Test fun sustainedStreamingThroughput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val input = whisperSamples(instrumentation.context.assets.open("speech_with_noise.wav").use { it.readBytes() })
        val rounds = 30
        val expected = input.size.toLong() * rounds
        var outputCount = 0L
        var maxFrameNs = 0L
        val start = System.nanoTime()
        GtcrnDenoiser(context.assets).use { model ->
            repeat(rounds) {
                for (offset in input.indices step 320) {
                    val frame = ShortArray(minOf(320, input.size - offset)) { (input[offset + it] * 32768f).toInt().coerceIn(-32768, 32767).toShort() }
                    val frameStart = System.nanoTime()
                    outputCount += model.process(frame).size
                    maxFrameNs = maxOf(maxFrameNs, System.nanoTime() - frameStart)
                }
            }
            outputCount += model.flush().size
        }
        val elapsed = (System.nanoTime() - start) / 1e9
        val duration = expected / 16000.0
        assertEquals(expected, outputCount)
        val report = "inputSeconds=$duration elapsedSeconds=$elapsed RTF=${elapsed / duration} maxFrameMs=${maxFrameNs / 1e6} samples=$outputCount"
        File(context.cacheDir, "denoise-throughput.txt").writeText(report)
        android.util.Log.i("DenoiseDeviceTest", report)
        assertTrue("Must keep up with real time: $report", elapsed < duration)
    }

    @Test fun streamedModelAndAacRoundTrip(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val input = whisperSamples(instrumentation.context.assets.open("speech_with_noise.wav").use { it.readBytes() })
        val output = File(context.cacheDir, "denoise-device-test.m4a")
        try {
            val model = GtcrnDenoiser(context.assets)
            var count = 0
            var changed = 0L
            val started = System.nanoTime()
            model.use { denoiser ->
                AacPcmSink(output, 64_000).use { sink ->
                    for (offset in input.indices step 320) {
                        val pcm = ShortArray(minOf(320, input.size - offset)) { (input[offset + it] * 32768f).toInt().coerceIn(-32768, 32767).toShort() }
                        val cleaned = denoiser.process(pcm)
                        for (sample in cleaned) {
                            if (count < input.size && abs(sample / 32768f - input[count]) > 0.0001f) changed++
                            count++
                        }
                        sink.write(cleaned)
                    }
                    val tail = denoiser.flush()
                    count += tail.size
                    sink.write(tail)
                }
            }
            assertEquals(input.size, count)
            assertTrue("Model must transform the noisy input", changed > input.size / 10)
            assertTrue(output.length() > 1000)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                val format = extractor.getTrackFormat(0)
                assertEquals(16_000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                assertTrue(abs(format.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0 - input.size / 16000.0) < 0.3)
            } finally {
                extractor.release()
            }
            var decoded = 0
            var energy = 0.0
            AndroidAudioChunks().read(output) { chunk ->
                val samples = whisperSamples(chunk.wav)
                decoded += samples.size
                samples.forEach { energy += it * it }
            }
            assertTrue(abs(decoded - input.size) < 4800)
            assertTrue(energy > 0)
            android.util.Log.i("DenoiseDeviceTest", "input=${input.size}, decoded=$decoded, elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
        } finally {
            output.delete()
        }
    }
}
