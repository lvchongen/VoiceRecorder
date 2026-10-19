package com.github.meetingrecorder.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Each file is hash-verified before rename; the package is ready only after every file is present.
 * Successfully downloaded files survive cancellation so a retry need not download them again. */
class QwenModelStore(
    private val directory: File,
    private val assets: List<WhisperModel> = FILES,
    downloadUrl: (WhisperModel) -> String = { "$BASE_URL/${it.filename}" },
) {
    private val mutex = Mutex()
    private val files = WhisperModelStore(directory, downloadUrl)
    fun installed() = assets.all { files.installed(it) }

    suspend fun <T> withModel(block: suspend (File) -> T): T = mutex.withLock {
        check(installed()) { "请先在设置中下载 Qwen3-ASR 模型" }
        block(directory)
    }

    suspend fun download(progress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val total = assets.sumOf { it.bytes }
            var completed = 0L
            for (asset in assets) {
                files.file(asset).parentFile!!.mkdirs()
                if (!files.installed(asset)) {
                    files.download(asset) { percent ->
                        progress(((completed + asset.bytes * percent / 100) * 100 / total).toInt())
                    }
                }
                completed += asset.bytes
                progress((completed * 100 / total).toInt())
            }
        }
    }

    suspend fun delete() = withContext(Dispatchers.IO) {
        mutex.withLock { check(!directory.exists() || directory.deleteRecursively()) { "模型删除失败" } }
    }

    companion object {
        const val MODEL_ID = "qwen3-asr-0.6b-int8"
        const val BASE_URL = "https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/68818b2313fe77bd06f6a7c5068ff3ef59d02b8a"
        val FILES = listOf(
            WhisperModel("qwen", "conv_frontend.onnx", 44148281L, "d22dc4423e0940e49884e903d2ea2f7e5567c14fc1aed97e4e26d6b8f208ef9e"),
            WhisperModel("qwen", "decoder.int8.onnx", 755914231L, "4f6885be5959ae26af3089d38ee7972c5fafbeeb1cf8d5e76eab6d8b61ca5771"),
            WhisperModel("qwen", "encoder.int8.onnx", 182491662L, "60748d3e6744a57c9c91e1b17424a6c2990567e8adceb0783940c03ed98fa9d9"),
            WhisperModel("qwen", "tokenizer/merges.txt", 1671853L, "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5"),
            WhisperModel("qwen", "tokenizer/tokenizer_config.json", 12487L, "4942d005604266809309cabc9f4e9cb89ce855d59b14681fdc0e1cc62ea26c4c"),
            WhisperModel("qwen", "tokenizer/vocab.json", 2776833L, "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910"),
        )
        val sizeLabel: String get() = "${FILES.sumOf { it.bytes } / 1024 / 1024} MiB"
    }
}
