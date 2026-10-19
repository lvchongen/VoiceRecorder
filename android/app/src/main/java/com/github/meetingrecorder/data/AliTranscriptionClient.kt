package com.github.meetingrecorder.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class AliTranscriptionClient(
    private val config: Config,
    private val chunks: AudioChunks = AndroidAudioChunks(),
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
    private val baseUrlOverride: String? = null,
) : TranscriptionClient {
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS).callTimeout(240, TimeUnit.SECONDS).build()

    override suspend fun transcribe(audioFile: File, mimeType: String, onStatus: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val key = config.aliApiKey.trim()
        val model = config.aliModel.trim()
        val base = baseUrlOverride ?: config.aliBaseUrl.trim()
        require(key.isNotBlank() && model.isNotBlank()) { "Set your Alibaba Model Studio key and ASR model." }
        require(baseUrlOverride != null || base.startsWith("https://")) { "Alibaba API URL must use HTTPS." }
        val transcript = StringBuilder()
        var count = 0
        onStatus("Decoding audio for Alibaba transcription…")
        chunks.read(audioFile) { chunk ->
            coroutineContext.ensureActive()
            count++
            onStatus("Alibaba transcription: segment $count (${chunk.startSeconds}s)…")
            val body = buildJsonObject {
                put("model", model)
                put("stream", false)
                put("asr_options", buildJsonObject { put("enable_itn", false) })
                put(
                    "messages",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put(
                                    "content",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("type", "input_audio")
                                                put(
                                                    "input_audio",
                                                    buildJsonObject {
                                                        put("data", "data:audio/wav;base64," + Base64.getEncoder().encodeToString(chunk.wav))
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            }.toString()
            val request = Request.Builder().url("${base.trimEnd('/')}/chat/completions")
                .header("Authorization", "Bearer $key").post(body.toRequestBody("application/json".toMediaType())).build()
            val text = recognize(request)
            val seconds = chunk.startSeconds
            val timestamp = "%02d:%02d:%02d".format(java.util.Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
            transcript.append("[$timestamp] ").append(text).append("\n\n")
        }
        check(count > 0) { "No audio could be decoded from this recording." }
        transcript.toString().trim()
    }

    private suspend fun recognize(request: Request): String {
        for (attempt in 0..2) {
            coroutineContext.ensureActive()
            try {
                return client.newCall(request).awaitResponse().use { response ->
                    if (!response.isSuccessful) throw AliHttpException(response.code)
                    val choice = lenientJson.parseToJsonElement(response.body?.string().orEmpty())
                        .jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: error("Alibaba returned no transcription result.")
                    check(choice["finish_reason"]?.jsonPrimitive?.contentOrNull == "stop") { "Alibaba returned an incomplete transcript; no partial result was saved." }
                    choice["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull?.trim()
                        ?.takeIf { it.isNotEmpty() } ?: error("Alibaba returned an empty segment. Check the recording before retrying.")
                }
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                val retryable = e is IOException || (e is AliHttpException && (e.code == 429 || e.code >= 500))
                if (!retryable || attempt == 2) throw e
                delayFn(2_000L shl attempt)
            }
        }
        error("Alibaba transcription failed.")
    }
}

private class AliHttpException(val code: Int) : RuntimeException(
    "Alibaba transcription failed (HTTP $code). Check the API key, account region, model access and balance.",
)
