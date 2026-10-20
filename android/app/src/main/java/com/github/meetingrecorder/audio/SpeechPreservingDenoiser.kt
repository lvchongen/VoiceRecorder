package com.github.meetingrecorder.audio

import java.util.ArrayDeque
import kotlin.math.pow

/**
 * Limits suppression after compensating DPDFNet8's four-hop (640 sample) model delay.
 * Keep this delay pinned to the shipped model/runtime; mixing unaligned PCM causes echoes.
 * Only pending input is retained, so memory does not grow with meeting duration.
 */
class SpeechPreservingDenoiser(
    private val wet: StreamingDenoiser,
    private val delaySamples: Int = 640,
    attenuationLimitDb: Float = 6f,
) : StreamingDenoiser {
    private val dryWeight = 10.0.pow(-attenuationLimitDb / 20.0).toFloat()
    private val pending = ArrayDeque<ShortArray>()
    private var headOffset = 0
    private var skip = delaySamples
    private var finished = false
    private var inputCount = 0L
    private var outputCount = 0L

    init {
        require(delaySamples >= 0)
        require(attenuationLimitDb.isFinite() && attenuationLimitDb > 0)
    }

    override fun process(samples: ShortArray): ShortArray {
        check(!finished) { "降噪已经结束" }
        if (samples.isEmpty()) return shortArrayOf()
        pending.addLast(samples.copyOf())
        inputCount += samples.size
        val output = mix(wet.process(samples))
        check(inputCount - outputCount <= delaySamples.toLong() + samples.size + 320) {
            "降噪输出积压，已保留原始录音"
        }
        return output
    }

    override fun flush(): ShortArray {
        check(!finished) { "降噪已经结束" }
        finished = true
        // Drain the delayed speech, without treating padding as new microphone input.
        val drained = mix(wet.process(ShortArray(delaySamples)))
        val tail = mix(wet.flush())
        check(pending.isEmpty() && inputCount == outputCount) { "降噪音频时长不完整，已保留原始录音" }
        return drained + tail
    }

    private fun mix(enhanced: ShortArray): ShortArray {
        val discarded = minOf(skip, enhanced.size)
        skip -= discarded
        return ShortArray(enhanced.size - discarded) { index ->
            val original = checkNotNull(pending.peekFirst()) { "降噪输出超出录音时长" }
            val dry = original[headOffset++].toFloat()
            if (headOffset == original.size) {
                pending.removeFirst()
                headOffset = 0
            }
            outputCount++
            (dryWeight * dry + (1f - dryWeight) * enhanced[index + discarded])
                .toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    override fun close() {
        pending.clear()
        wet.close()
    }
}
