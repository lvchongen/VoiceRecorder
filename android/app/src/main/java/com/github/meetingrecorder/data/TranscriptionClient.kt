package com.github.meetingrecorder.data

import java.io.File

enum class TranscriptionProvider(val label: String) { GEMINI("Gemini"), ALIBABA("阿里云"), WHISPER("Whisper"), QWEN("Qwen3-ASR") }

class NoSpeechDetectedException(message: String) : IllegalStateException(message)

interface TranscriptionClient {
    /** Keep local model resources alive for all calls in one transcription operation. */
    suspend fun <T> withSession(onStatus: (String) -> Unit = {}, block: suspend (TranscriptionClient) -> T): T = block(this)

    suspend fun transcribe(audioFile: File, mimeType: String = "audio/mp4", onStatus: (String) -> Unit = {}): String
}

class SelectedTranscriptionClient(
    private val config: Config,
    private val gemini: TranscriptionClient,
    private val ali: TranscriptionClient,
    private val whisper: TranscriptionClient,
    private val qwen: TranscriptionClient,
) : TranscriptionClient {
    private fun selected(): TranscriptionClient {
        config.processingError(true)?.let { error(it) }
        return when (config.transcriptionProvider) {
            TranscriptionProvider.GEMINI -> gemini
            TranscriptionProvider.ALIBABA -> ali
            TranscriptionProvider.WHISPER -> if (config.whisperModel == QwenModelStore.MODEL_ID) qwen else whisper
            TranscriptionProvider.QWEN -> qwen
        }
    }

    override suspend fun <T> withSession(onStatus: (String) -> Unit, block: suspend (TranscriptionClient) -> T): T =
        selected().withSession(onStatus, block)

    override suspend fun transcribe(audioFile: File, mimeType: String, onStatus: (String) -> Unit): String =
        selected().transcribe(audioFile, mimeType, onStatus)
}
