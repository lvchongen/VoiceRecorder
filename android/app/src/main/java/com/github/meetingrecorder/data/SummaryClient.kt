package com.github.meetingrecorder.data

interface SummaryClient {
    suspend fun summarize(transcript: String, onStatus: (String) -> Unit = {}): String
    suspend fun summarizeWithOptions(transcript: String, options: SummaryOptions, onStatus: (String) -> Unit = {}): String =
        summarize(transcript, onStatus)
    suspend fun generateTitle(notes: String, onStatus: (String) -> Unit = {}): String
}

enum class SummaryProvider(val label: String) {
    GEMINI("Gemini"),
    DEEPSEEK("DeepSeek"),
}

fun processingConfigurationError(
    needsTranscription: Boolean,
    geminiKey: String,
    provider: SummaryProvider,
    deepSeekKey: String,
    deepSeekModel: String,
    transcriptionProvider: TranscriptionProvider = TranscriptionProvider.GEMINI,
    aliKey: String = "",
    aliBaseUrl: String = "",
    aliModel: String = "",
): String? = when {
    ((needsTranscription && transcriptionProvider == TranscriptionProvider.GEMINI) || provider == SummaryProvider.GEMINI) && geminiKey.isBlank() ->
        "Add a Gemini API key in Settings for audio transcription or Gemini summaries."
    needsTranscription && transcriptionProvider == TranscriptionProvider.ALIBABA && aliKey.isBlank() ->
        "Add an Alibaba Cloud Model Studio API key in Settings."
    needsTranscription && transcriptionProvider == TranscriptionProvider.ALIBABA && (!aliBaseUrl.startsWith("https://") || aliModel.isBlank()) ->
        "Set the Alibaba HTTPS API base URL and ASR model in Settings."
    provider == SummaryProvider.DEEPSEEK && deepSeekKey.isBlank() ->
        "Add a DeepSeek API key in Settings for summaries."
    provider == SummaryProvider.DEEPSEEK && deepSeekModel.isBlank() ->
        "Choose a DeepSeek model in Settings."
    else -> null
}

/** Read settings at request time so changing providers does not require restarting the app. */
class SelectedSummaryClient(
    private val config: Config,
    private val gemini: SummaryClient,
    private val deepSeek: SummaryClient,
) : SummaryClient {
    private fun selected(): SummaryClient {
        config.processingError(needsTranscription = false)?.let { error(it) }
        return if (config.summaryProvider == SummaryProvider.DEEPSEEK) deepSeek else gemini
    }

    override suspend fun summarize(transcript: String, onStatus: (String) -> Unit): String =
        selected().summarize(transcript, onStatus)

    override suspend fun summarizeWithOptions(transcript: String, options: SummaryOptions, onStatus: (String) -> Unit): String =
        selected().summarizeWithOptions(transcript, options, onStatus)

    override suspend fun generateTitle(notes: String, onStatus: (String) -> Unit): String =
        selected().generateTitle(notes, onStatus)
}

fun summaryLanguageInstruction(language: String?): String = when (language) {
    "Chinese" -> "Write all meeting notes in Simplified Chinese, preserving proper names and technical identifiers."
    "Original" -> "Write in the source language."
    else -> "Write bilingual Simplified Chinese and English notes. For each heading and each point, put Chinese first and the equivalent English immediately below. Pair each decision and action item; both versions must contain exactly the same facts, numbers, uncertainty and conditions. Do not add claims in translation."
}
