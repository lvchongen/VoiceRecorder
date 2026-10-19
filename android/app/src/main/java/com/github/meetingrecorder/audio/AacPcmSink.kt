package com.github.meetingrecorder.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder

/** Single-owner PCM16 mono encoder; timestamps derive from samples, never wall-clock time. */
class AacPcmSink(file: File, bitrate: Int) : PcmSink {
    private val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var samplesWritten = 0L
    private var closed = false
    private val info = MediaCodec.BufferInfo()
    init {
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 16_000, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate.coerceAtMost(96_000))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            encoder.release()
            throw e
        }
    }
    override fun write(samples: ShortArray) {
        check(!closed)
        var offset = 0
        val deadline = System.nanoTime() + 5_000_000_000L
        while (offset < samples.size) {
            check(System.nanoTime() < deadline) { "音频编码超时" }
            val index = encoder.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val buffer = requireNotNull(encoder.getInputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                buffer.clear()
                val count = minOf(samples.size - offset, buffer.remaining() / 2)
                check(count > 0)
                repeat(count) { buffer.putShort(samples[offset + it]) }
                encoder.queueInputBuffer(index, 0, count * 2, samplesWritten * 1_000_000 / 16_000, 0)
                samplesWritten += count
                offset += count
            }
            drain()
        }
    }
    private fun drain(): Boolean {
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(track == -1)
                    track = requireNotNull(muxer).addTrack(encoder.outputFormat)
                    muxer!!.start()
                }
                index >= 0 -> {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(track >= 0)
                            val buffer = requireNotNull(encoder.getOutputBuffer(index))
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer!!.writeSampleData(track, buffer, info)
                        }
                    } finally {
                        encoder.releaseOutputBuffer(index, false)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
                else -> return false
            }
        }
    }
    override fun close() {
        if (closed) return
        closed = true
        try {
            val deadline = System.nanoTime() + 5_000_000_000L
            var queued = false
            var ended = false
            while (!ended) {
                check(System.nanoTime() < deadline) { "音频保存超时" }
                if (!queued) {
                    val index = encoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        encoder.queueInputBuffer(index, 0, 0, samplesWritten * 1_000_000 / 16_000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        queued = true
                    }
                }
                ended = drain()
                if (!ended && queued) Thread.sleep(2)
            }
        } finally {
            try {
                encoder.stop()
            } finally {
                encoder.release()
                val m = muxer
                muxer = null
                try {
                    if (track >= 0) m?.stop()
                } finally {
                    m?.release()
                }
            }
        }
    }
}
