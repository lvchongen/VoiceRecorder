package com.github.meetingrecorder.audio

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

interface PcmSink : Closeable {
    fun write(samples: ShortArray)
}

interface StreamingDenoiser : Closeable {
    fun process(samples: ShortArray): ShortArray
    fun flush(): ShortArray
}

class GtcrnDenoiser(assets: AssetManager) : StreamingDenoiser {
    private val engine = OnlineSpeechDenoiser(
        assets,
        OnlineSpeechDenoiserConfig(
            model = OfflineSpeechDenoiserModelConfig(
                gtcrn = OfflineSpeechDenoiserGtcrnModelConfig("denoise/gtcrn_simple.onnx"),
                numThreads = 1,
                provider = "cpu",
            ),
        ),
    )
    init {
        check(engine.sampleRate == 16_000)
    }
    override fun process(samples: ShortArray) = toPcm(engine.run(FloatArray(samples.size) { samples[it] / 32768f }, 16_000).samples)
    override fun flush() = toPcm(engine.flush().samples)
    override fun close() = engine.release()

    private fun toPcm(samples: FloatArray) = ShortArray(samples.size) {
        check(samples[it].isFinite()) { "降噪返回了无效音频" }
        (samples[it] * 32768f).toInt().coerceIn(-32768, 32767).toShort()
    }
}

/** Bounded side branch: an overloaded/failed denoiser never blocks microphone capture. */
class DenoisingBranch(
    private val engine: StreamingDenoiser,
    private val output: PcmSink,
    private val onFailure: (String) -> Unit,
    capacity: Int = 100,
) {
    private val queue = ArrayBlockingQueue<ShortArray>(capacity)

    @Volatile private var finished = false

    @Volatile var failure: String? = null
        private set
    var inputSamples = 0L
        private set
    var outputSamples = 0L
        private set
    private val worker = Thread({ run() }, "gtcrn-denoise").apply { start() }

    fun accept(samples: ShortArray) {
        check(!finished)
        inputSamples += samples.size
        if (failure == null && !queue.offer(samples.copyOf())) fail("降噪速度不足，已保留原始录音")
    }

    fun finish(): Boolean {
        finished = true
        worker.join()
        return failure == null
    }

    @Synchronized private fun fail(message: String) {
        if (failure == null) {
            failure = message
            onFailure(message)
        }
    }

    private fun write(samples: ShortArray) {
        if (samples.isNotEmpty()) output.write(samples)
        outputSamples += samples.size
    }

    private fun run() {
        try {
            while (failure == null && (!finished || queue.isNotEmpty())) {
                val samples = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                write(engine.process(samples))
            }
            if (failure == null) {
                write(engine.flush())
                check(inputSamples == outputSamples) { "降噪音频时长不完整，已保留原始录音" }
            }
        } catch (e: Exception) {
            fail(e.message ?: "降噪失败，已保留原始录音")
        } finally {
            queue.clear()
            try {
                output.close()
            } catch (e: Exception) {
                fail(e.message ?: "降噪音频保存失败")
            }
            try {
                engine.close()
            } catch (e: Exception) {
                fail(e.message ?: "降噪资源释放失败")
            }
        }
    }
}
