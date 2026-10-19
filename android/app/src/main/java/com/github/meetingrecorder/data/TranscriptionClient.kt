package com.github.meetingrecorder.data

import java.io.File

enum class TranscriptionProvider(val label: String) { GEMINI("Gemini"), ALIBABA("阿里云"), WHISPER("Whisper"), QWEN("Qwen3-ASR") }

class NoSpeechDetectedException(message: String) : IllegalStateException(message)

interface TranscriptionClient {
    suspend fun transcribe(audioFile: File, mimeType: String = "audio/mp4", onStatus: (String) -> Unit = {}): String
}

class SelectedTranscriptionClient(
    private val config: Config,
    private val gemini: TranscriptionClient,
    private val ali: TranscriptionClient,
    private val whisper: TranscriptionClient,
    private val qwen: TranscriptionClient,
) : TranscriptionClient {
    override suspend fun transcribe(audioFile: File, mimeType: String, onStatus: (String) -> Unit): String {
        config.processingError(true)?.let { error(it) }
        return when (config.transcriptionProvider) {
            TranscriptionProvider.GEMINI -> gemini
            TranscriptionProvider.ALIBABA -> ali
            TranscriptionProvider.WHISPER -> if (config.whisperModel == QwenModelStore.MODEL_ID) qwen else whisper
            TranscriptionProvider.QWEN -> qwen
        }.transcribe(audioFile, mimeType, onStatus)
    }
}
