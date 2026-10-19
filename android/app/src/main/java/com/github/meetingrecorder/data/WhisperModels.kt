package com.github.meetingrecorder.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class WhisperModel(val id: String, val filename: String, val bytes: Long, val sha256: String) {
    val sizeLabel: String get() = "${bytes / 1024 / 1024} MiB"
    val url: String get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$filename"
    companion object {
        val all = listOf(
            WhisperModel("tiny", "ggml-tiny-q5_1.bin", 32152673L, "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7"),
            WhisperModel("base", "ggml-base-q5_1.bin", 59707625L, "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"),
            WhisperModel("small", "ggml-small-q5_1.bin", 190085487L, "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"),
            WhisperModel("medium", "ggml-medium-q5_0.bin", 539212467L, "19fea4b380c3a618ec4723c3eef2eb785ffba0d0538cf43f8f235e7b3b34220f"),
            WhisperModel("large-v3", "ggml-large-v3-q5_0.bin", 1081140203L, "d75795ecff3f83b5faa89d1900604ad8c780abd5739fae406de19f23ecd98ad1"),
            WhisperModel("large-v3-turbo", "ggml-large-v3-turbo-q5_0.bin", 574041195L, "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2"),
        )
        fun selected(id: String) = all.firstOrNull { it.id == id } ?: all.first { it.id == "medium" }
    }
}

class WhisperModelStore(private val directory: File, private val downloadUrl: (WhisperModel) -> String = { it.url }) {
    private val mutex = Mutex()
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    fun file(model: WhisperModel) = File(directory, model.filename)
    fun installed(model: WhisperModel) = file(model).isFile && file(model).length() == model.bytes

    suspend fun <T> withModel(model: WhisperModel, block: suspend (File) -> T): T = mutex.withLock {
        check(installed(model)) { "请先在设置中下载所选 Whisper 模型" }
        block(file(model))
    }

    suspend fun delete(model: WhisperModel) = withContext(Dispatchers.IO) {
        mutex.withLock { check(!file(model).exists() || file(model).delete()) { "模型删除失败" } }
    }

    suspend fun download(model: WhisperModel, progress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            directory.mkdirs()
            val temporary = File(directory, model.filename + ".part")
            try {
                client.newCall(Request.Builder().url(downloadUrl(model)).build()).awaitResponse().use { response ->
                    check(response.isSuccessful) { "模型下载失败（HTTP ${response.code}）" }
                    val body = requireNotNull(response.body)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    var lastProgress = -1
                    body.byteStream().use { input ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                count += n
                                check(count <= model.bytes) { "模型文件大小不符" }
                                digest.update(buffer, 0, n)
                                output.write(buffer, 0, n)
                                val percent = (count * 100 / model.bytes).toInt()
                                if (percent != lastProgress) {
                                    progress(percent)
                                    lastProgress = percent
                                }
                            }
                        }
                    }
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    check(count == model.bytes && hash == model.sha256) { "模型校验失败，请重试" }
                    check(temporary.renameTo(file(model))) { "模型保存失败" }
                }
            } finally {
                temporary.delete()
            }
        }
    }
}
