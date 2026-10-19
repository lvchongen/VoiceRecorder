package com.github.meetingrecorder.data

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.coroutineContext

interface QwenEngine : AutoCloseable {
    fun transcribe(samples: FloatArray): String
}

private class SherpaQwenEngine(directory: File) : QwenEngine {
    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                qwen3Asr = OfflineQwen3AsrModelConfig(
                    convFrontend = File(directory, "conv_frontend.onnx").absolutePath,
                    encoder = File(directory, "encoder.int8.onnx").absolutePath,
                    decoder = File(directory, "decoder.int8.onnx").absolutePath,
                    tokenizer = File(directory, "tokenizer").absolutePath,
                    maxTotalLen = 1024,
                    maxNewTokens = 512,
                ),
                numThreads = minOf(4, Runtime.getRuntime().availableProcessors()),
                provider = "cpu",
            ),
        ),
    )

    override fun transcribe(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, 16000)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()
}

class QwenTranscriptionClient(
    private val models: QwenModelStore,
    private val chunks: AudioChunks = AndroidAudioChunks(),
    private val engineFactory: (File) -> QwenEngine = { SherpaQwenEngine(it) },
) : TranscriptionClient {
    override suspend fun transcribe(audioFile: File, mimeType: String, onStatus: (String) -> Unit): String = withContext(Dispatchers.IO) {
        models.withModel { directory ->
            coroutineContext.ensureActive()
            onStatus("正在加载 Qwen3-ASR…")
            engineFactory(directory).use { engine ->
                val output = StringBuilder()
                chunks.read(audioFile) { chunk ->
                    val samples = whisperSamples(chunk.wav)
                    // Bound decoder output and cancellation latency. Native decode cannot be interrupted;
                    // cancellation is checked before/after each 20-second audio segment, never freeing a live decoder.
                    var offset = 0
                    while (offset < samples.size) {
                        coroutineContext.ensureActive()
                        val end = minOf(offset + 20 * 16000, samples.size)
                        val seconds = chunk.startSeconds + offset / 16000
                        onStatus("Qwen3-ASR 正在识别：$seconds 秒…")
                        val text = engine.transcribe(samples.copyOfRange(offset, end))
                        coroutineContext.ensureActive()
                        if (text.isNotBlank()) {
                            output.append("[%02d:%02d:%02d] ".format(Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60))
                                .append(text).append("\n\n")
                        }
                        offset = end
                    }
                }
                check(output.isNotBlank()) { "未识别到语音，请检查音频" }
                output.toString().trim()
            }
        }
    }
}
