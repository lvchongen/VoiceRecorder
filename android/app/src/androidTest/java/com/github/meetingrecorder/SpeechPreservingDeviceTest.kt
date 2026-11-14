package com.github.meetingrecorder

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.meetingrecorder.audio.AacPcmSink
import com.github.meetingrecorder.audio.ModelDenoiser
import com.github.meetingrecorder.data.DenoiseModel
import com.github.meetingrecorder.data.whisperSamples
import com.github.meetingrecorder.speakers.pcmWave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sqrt

/** Opt-in local-file regression. No microphone, network, or edits to saved meetings. */
@RunWith(AndroidJUnit4::class)
class SpeechPreservingDeviceTest {
    @Test fun preservesApprovedRecording() {
        val arguments = InstrumentationRegistry.getArguments()
        val inputPath = arguments.getString("denoiseInput")
        val referencePath = arguments.getString("denoiseReference")
        assumeTrue(inputPath != null && referencePath != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val inputFile = File(requireNotNull(inputPath))
        val referenceFile = File(requireNotNull(referencePath))
        // This diagnostic only accepts short PCM16/16kHz fixtures staged in private cache.
        for (file in listOf(inputFile, referenceFile)) {
            assertTrue(file.canonicalPath.startsWith(context.cacheDir.canonicalPath + "/"))
            assertTrue(file.length() in 45..2_000_000)
        }
        val input = whisperSamples(inputFile.readBytes())
        val reference = whisperSamples(referenceFile.readBytes())
        assertEquals(input.size, reference.size)
        val output = ShortArray(input.size)
        var count = 0
        fun collect(frame: ShortArray) {
            assertTrue(count + frame.size <= output.size)
            frame.copyInto(output, count)
            count += frame.size
        }
        val start = System.nanoTime()
        ModelDenoiser(context.assets, DenoiseModel.DPDFNET8).use { model ->
            for (offset in input.indices step 320) {
                val frame = ShortArray(minOf(320, input.size - offset)) {
                    (input[offset + it] * 32768f).toInt().coerceIn(-32768, 32767).toShort()
                }
                collect(model.process(frame))
            }
            collect(model.flush())
        }
        val elapsed = (System.nanoTime() - start) / 1e9
        assertEquals(input.size, count)
        val samples = FloatArray(output.size) { output[it] / 32768f }
        var errorEnergy = 0.0
        var referenceEnergy = 0.0
        var outputEnergy = 0.0
        var cross = 0.0
        samples.indices.forEach {
            val a = samples[it].toDouble()
            val b = reference[it].toDouble()
            errorEnergy += (a - b) * (a - b)
            referenceEnergy += b * b
            outputEnergy += a * a
            cross += a * b
        }
        val correlation = cross / sqrt(referenceEnergy * outputEnergy)
        val relativeError = sqrt(errorEnergy / referenceEnergy)
        val duration = input.size / 16000.0
        val report = "samples=$count seconds=$duration elapsedSeconds=$elapsed RTF=${elapsed / duration} correlation=$correlation relativeRmsError=$relativeError"
        File(context.cacheDir, "denoise-regression.txt").writeText(report)
        File(context.cacheDir, "denoise-regression.wav").writeBytes(pcmWave(samples))
        AacPcmSink(File(context.cacheDir, "denoise-regression.m4a"), 96_000).use { sink ->
            for (offset in output.indices step 320) sink.write(output.copyOfRange(offset, minOf(offset + 320, output.size)))
        }
        android.util.Log.i("SpeechPreservingDeviceTest", report)
        assertTrue("Must match approved candidate: $report", correlation > 0.999 && relativeError < 0.04)
        assertTrue("Must keep up with real time: $report", elapsed < duration)
    }
}
