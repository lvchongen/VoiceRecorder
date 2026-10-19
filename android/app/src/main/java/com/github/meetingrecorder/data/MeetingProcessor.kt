package com.github.meetingrecorder.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "MeetingProcessor"

/** Result of the full transcribe → summarize workflow. */
data class ProcessingResult(val transcript: String, val notes: String)

/**
 * Owns the meeting-processing workflows shared by the record flow (MainViewModel) and the
 * detail-screen generation flows (MeetingDetailViewModel): Gemini transcription /
 * summarization, best-effort title generation, and the transcript.md / notes.md / meeting.json
 * disk writes. Everything is constructor-injected ([GeminiClient], [MeetingStore], dispatcher,
 * log sink) so the whole workflow is JVM unit-testable; the ViewModels keep only state management
 * and platform glue (recording service, lock files, countdowns, content resolvers).
 */
class MeetingProcessor(
    private val gemini: GeminiClient,
    private val store: MeetingStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    // Injectable so JVM tests don't hit android.util.Log.
    private val logWarn: (String, Exception) -> Unit = { msg, e -> Log.w(TAG, msg, e) },
    private val summaryClient: SummaryClient = gemini,
    private val transcriptionClient: TranscriptionClient = gemini,
    private val speakerTranscriptionClient: TranscriptionClient? = null,
    private val onAudioSaved: (File) -> Unit = {},
) {

    /** Upload → transcribe → summarize. Progress text for the UI arrives via [onStatus]. */
    suspend fun transcribeAndSummarize(
        audioFile: File,
        mimeType: String = "audio/mp4",
        meetingDir: File = requireNotNull(audioFile.parentFile),
        onTranscriptSaved: (String) -> Unit = {},
        summaryOptions: SummaryOptions? = null,
        saveNotesCheckpoint: Boolean = true,
        withSpeakers: Boolean = false,
        onStatus: (String) -> Unit = {},
    ): ProcessingResult {
        val client = if (withSpeakers) requireNotNull(speakerTranscriptionClient) { "说话人转写未配置" } else transcriptionClient
        val transcript = client.transcribe(audioFile, mimeType, onStatus)
        saveCheckpoint(meetingDir, "transcript.md", transcript)
        onTranscriptSaved(transcript)
        val notes = if (summaryOptions == null) summaryClient.summarize(transcript, onStatus) else summaryClient.summarizeWithOptions(transcript, summaryOptions, onStatus)
        if (saveNotesCheckpoint) saveCheckpoint(meetingDir, "notes.md", notes)
        return ProcessingResult(transcript, notes)
    }

    /** Notes-only generation reusing an existing transcript — no audio upload. */
    suspend fun summarizeTranscript(
        transcript: String,
        onStatus: (String) -> Unit = {},
    ): String = summaryClient.summarize(transcript, onStatus)

    suspend fun previewSummary(transcript: String, options: SummaryOptions, onStatus: (String) -> Unit = {}): String =
        summaryClient.summarizeWithOptions(transcript, options, onStatus)

    suspend fun summaryVersions(dir: File): List<SummaryVersion> = withContext(ioDispatcher) { SummaryHistory().list(dir) }

    suspend fun adoptSummary(dir: File, version: SummaryVersion) = withContext(ioDispatcher) {
        SummaryHistory().adopt(dir, version)
    }

    suspend fun saveMetadata(dir: File, title: String?, durationSeconds: Int?) = withContext(ioDispatcher) {
        store.saveMeetingMeta(dir, title, durationSeconds)
    }

    /**
     * Best-effort title generation: returns the trimmed title, or null on failure (logged, never
     * thrown) so a title problem can't fail the surrounding flow — the meeting stays untitled.
     */
    suspend fun generateTitle(notes: String): String? = try {
        summaryClient.generateTitle(notes).trim()
    } catch (e: CancellationException) {
        // Never swallow cancellation: the caller's coroutine must stop instead
        // of continuing to write results after the user cancelled.
        throw e
    } catch (e: Exception) {
        logWarn("Title generation failed; keeping meeting untitled", e)
        null
    }

    /** Commit each completed stage independently of later network calls. */
    private suspend fun saveCheckpoint(dir: File, name: String, text: String) = withContext(ioDispatcher) {
        writeAtomically(File(dir, name), text)
    }

    private fun writeAtomically(target: File, text: String) = atomicText(target, text)

    /** Finalize a recording without starting transcription or summary requests. */
    suspend fun saveAudioOnly(meetingDir: File, title: String?, durationSeconds: Int?) = withContext(ioDispatcher) {
        store.saveMeetingMeta(meetingDir, title, durationSeconds)
        val lock = File(meetingDir, ".recording")
        check(!lock.exists() || lock.delete()) { "无法完成录音保存，请检查存储权限" }
        onAudioSaved(meetingDir)
    }

    /** Writes transcript.md + notes.md and saves the meeting.json metadata. */
    suspend fun saveResults(
        meetingDir: File,
        transcript: String,
        notes: String,
        title: String?,
        durationSeconds: Int?,
    ) = withContext(ioDispatcher) {
        writeAtomically(File(meetingDir, "transcript.md"), transcript)
        writeAtomically(File(meetingDir, "notes.md"), notes)
        store.saveMeetingMeta(meetingDir, title, durationSeconds)
        onAudioSaved(meetingDir)
    }

    /** Writes notes.md and saves the meeting.json metadata (the notes-only path). */
    suspend fun saveNotes(
        meetingDir: File,
        notes: String,
        title: String?,
        durationSeconds: Int?,
    ) = withContext(ioDispatcher) {
        writeAtomically(File(meetingDir, "notes.md"), notes)
        store.saveMeetingMeta(meetingDir, title, durationSeconds)
    }
}
