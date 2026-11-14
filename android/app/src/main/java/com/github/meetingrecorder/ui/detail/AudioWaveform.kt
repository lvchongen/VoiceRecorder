package com.github.meetingrecorder.ui.detail

import com.github.meetingrecorder.data.AndroidAudioChunks
import com.github.meetingrecorder.data.AudioChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** Constant-size envelope from the PCM WAV chunks produced by AndroidAudioChunks. */
internal class WaveformEnvelope(private val durationMs: Int, private val bins: Int = 72) {
    private val peaks = FloatArray(bins)

    fun add(chunk: AudioChunk) {
        if (durationMs <= 0 || chunk.wav.size < 44) return
        val buffer = ByteBuffer.wrap(chunk.wav).order(ByteOrder.LITTLE_ENDIAN)
        val channels = buffer.getShort(22).toInt()
        val rate = buffer.getInt(24)
        require(channels in 1..2 && rate > 0)
        val frames = (chunk.wav.size - 44) / (channels * 2)
        for (frame in 0 until frames) {
            val ms = chunk.startMilliseconds + frame * 1000L / rate
            val bin = (ms * bins / durationMs).toInt().coerceIn(0, bins - 1)
            for (channel in 0 until channels) {
                val amplitude = abs(buffer.getShort(44 + (frame * channels + channel) * 2).toInt()) / 32768f
                peaks[bin] = maxOf(peaks[bin], amplitude)
            }
        }
    }

    fun values(): List<Float> {
        val max = peaks.maxOrNull()?.coerceAtLeast(0.01f) ?: 1f
        return peaks.map { sqrt(it / max) }
    }
}

internal suspend fun readAudioWaveform(file: File, durationMs: Int): List<Float> = withContext(Dispatchers.IO) {
    val envelope = WaveformEnvelope(durationMs)
    AndroidAudioChunks().read(file) { envelope.add(it) }
    envelope.values()
}
