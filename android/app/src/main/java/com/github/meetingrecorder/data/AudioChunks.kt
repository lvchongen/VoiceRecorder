package com.github.meetingrecorder.data

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/** A bounded WAV segment, with its real position in decoded audio (not speaker timestamps). */
data class AudioChunk(val wav: ByteArray, val startSeconds: Long)

fun interface AudioChunks {
    suspend fun read(file: File, consume: suspend (AudioChunk) -> Unit)
}

/** Pure PCM framing keeps a long recording out of memory; never discards boundary samples. */
class PcmChunker(private val sampleRate: Int, private val channels: Int, private val maxBytes: Int = 4_000_000) {
    private val frameBytes = channels * 2
    private val limit = minOf(sampleRate.toLong() * frameBytes * 60, maxBytes.toLong()).toInt() / frameBytes * frameBytes
    private val pending = ByteArrayOutputStream()
    private var emittedBytes = 0L

    init {
        require(sampleRate in 8000..192000 && channels in 1..2 && limit >= frameBytes)
    }

    suspend fun add(bytes: ByteArray, consume: suspend (AudioChunk) -> Unit) {
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(limit - pending.size(), bytes.size - offset)
            pending.write(bytes, offset, count)
            offset += count
            if (pending.size() == limit) emit(consume)
        }
    }

    suspend fun finish(consume: suspend (AudioChunk) -> Unit) {
        check(pending.size() % frameBytes == 0) { "Incomplete PCM audio frame." }
        if (pending.size() > 0) emit(consume)
    }

    private suspend fun emit(consume: suspend (AudioChunk) -> Unit) {
        val pcm = pending.toByteArray()
        pending.reset()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray())
        header.putInt(16).putShort(1).putShort(channels.toShort()).putInt(sampleRate)
        header.putInt(sampleRate * frameBytes).putShort(frameBytes.toShort()).putShort(16)
        header.put("data".toByteArray()).putInt(pcm.size)
        val chunk = AudioChunk(header.array() + pcm, emittedBytes / (sampleRate.toLong() * frameBytes))
        emittedBytes += pcm.size
        consume(chunk)
    }
}

/** Decode the first audio track with Android codecs; no external storage upload or FFmpeg needed. */
class AndroidAudioChunks : AudioChunks {
    override suspend fun read(file: File, consume: suspend (AudioChunk) -> Unit) = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No supported audio track found.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            if (format.getString(MediaFormat.KEY_MIME) == "audio/raw") {
                val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                check(encoding == AudioFormat.ENCODING_PCM_16BIT) { "Please convert this WAV to 16-bit PCM first." }
                val raw = PcmChunker(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                val buffer = ByteBuffer.allocate(256 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    buffer.position(0)
                    raw.add(ByteArray(size).also { buffer.get(it) }, consume)
                    extractor.advance()
                }
                raw.finish(consume)
                return@withContext
            }
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()
            var chunker: PcmChunker? = null
            var inputEnded = false
            var outputEnded = false
            var idleLoops = 0
            val info = MediaCodec.BufferInfo()
            while (!outputEnded) {
                coroutineContext.ensureActive()
                var progressed = false
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(index))
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        progressed = true
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    check(chunker == null) { "Audio format changed in the middle of the recording." }
                    val output = codec.outputFormat
                    val encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) output.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    check(encoding == AudioFormat.ENCODING_PCM_16BIT) { "This audio decoder does not provide 16-bit PCM." }
                    chunker = PcmChunker(output.getInteger(MediaFormat.KEY_SAMPLE_RATE), output.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    progressed = true
                } else if (index >= 0) {
                    val bytes = if (info.size > 0) {
                        val buffer = requireNotNull(codec.getOutputBuffer(index))
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        ByteArray(info.size).also { buffer.get(it) }
                    } else {
                        ByteArray(0)
                    }
                    codec.releaseOutputBuffer(index, false)
                    requireNotNull(chunker) { "Missing decoder output format." }.add(bytes, consume)
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    progressed = true
                }
                idleLoops = if (progressed) 0 else idleLoops + 1
                check(idleLoops < 1500) { "Audio decoder stalled. Try converting the recording to M4A or WAV." }
            }
            chunker?.finish(consume)
            Unit
        } finally {
            decoder?.release()
            extractor.release()
        }
    }
}
