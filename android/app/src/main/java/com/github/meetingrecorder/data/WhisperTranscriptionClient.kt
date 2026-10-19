package com.github.meetingrecorder.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.min

class WhisperNative {
    companion object {
        init {
            System.loadLibrary("meeting_whisper")
        }
    }
    external fun open(path: String): Long
    external fun transcribe(handle: Long, samples: FloatArray, threads: Int): ByteArray
    external fun cancel(handle: Long)
    external fun close(handle: Long)
}

/** Convert the generated PCM16 WAV to mono 16 kHz, averaging input samples when downsampling. */
fun whisperSamples(wav: ByteArray): FloatArray {
    require(wav.size >= 44)
    val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
    val channels = buffer.getShort(22).toInt()
    val rate = buffer.getInt(24)
    require(channels in 1..2 && rate in 8000..192000 && buffer.getShort(34).toInt() == 16)
    val frames = (wav.size - 44) / (2 * channels)
    val mono = FloatArray(frames) { frame ->
        var value = 0f
        repeat(channels) { channel -> value += buffer.getShort(44 + (frame * channels + channel) * 2) / 32768f }
        value / channels
    }
    if (rate == 16000) return mono
    val ratio = rate / 16000.0
    return FloatArray((frames / ratio).toInt()) { i ->
        val start = i * ratio
        if (ratio < 1) {
            val index = start.toInt()
            val fraction = (start - index).toFloat()
            mono[index] * (1 - fraction) + mono[min(index + 1, frames - 1)] * fraction
        } else {
            val end = min((i + 1) * ratio, frames.toDouble())
            var sum = 0.0
            for (j in start.toInt() until ceil(end).toInt()) {
                val weight = min(end, j + 1.0) - maxOf(start, j.toDouble())
                sum += mono[j] * weight
            }
            (sum / (end - start)).toFloat()
        }
    }
}

class WhisperTranscriptionClient(
    private val config: Config,
    private val models: WhisperModelStore,
    private val chunks: AudioChunks = AndroidAudioChunks(),
) : TranscriptionClient {
    @OptIn(InternalCoroutinesApi::class)
    override suspend fun transcribe(audioFile: File, mimeType: String, onStatus: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val model = WhisperModel.selected(config.whisperModel)
        models.withModel(model) { modelFile ->
            onStatus("Loading local Whisper ${model.id}…")
            val native = WhisperNative()
            val handle = native.open(modelFile.absolutePath)
            val guard = Any()
            var closed = false
            val cancellation = coroutineContext[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
                if (cause != null) synchronized(guard) { if (!closed) native.cancel(handle) }
            }
            try {
                val result = StringBuilder()
                var part = 0
                chunks.read(audioFile) { chunk ->
                    coroutineContext.ensureActive()
                    part++
                    onStatus("Local Whisper ${model.id}: segment $part (${chunk.startSeconds}s)…")
                    val text = native.transcribe(handle, whisperSamples(chunk.wav), minOf(4, Runtime.getRuntime().availableProcessors())).decodeToString().trim()
                    coroutineContext.ensureActive()
                    if (text.isNotBlank()) {
                        val s = chunk.startSeconds
                        result.append("[%02d:%02d:%02d] ".format(java.util.Locale.ROOT, s / 3600, s / 60 % 60, s % 60)).append(text).append("\n\n")
                    }
                }
                check(result.isNotBlank()) { "Whisper returned no speech. Check the audio or try another model." }
                result.toString().trim()
            } finally {
                cancellation?.dispose()
                synchronized(guard) {
                    closed = true
                    native.close(handle)
                }
            }
        }
    }
}
