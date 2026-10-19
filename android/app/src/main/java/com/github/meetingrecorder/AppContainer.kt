package com.github.meetingrecorder

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import com.github.meetingrecorder.data.AliTranscriptionClient
import com.github.meetingrecorder.data.Config
import com.github.meetingrecorder.data.DeepSeekClient
import com.github.meetingrecorder.data.GeminiClient
import com.github.meetingrecorder.data.MeetingProcessor
import com.github.meetingrecorder.data.MeetingRepository
import com.github.meetingrecorder.data.QwenModelStore
import com.github.meetingrecorder.data.QwenTranscriptionClient
import com.github.meetingrecorder.data.SelectedSummaryClient
import com.github.meetingrecorder.data.SelectedTranscriptionClient
import com.github.meetingrecorder.data.TranscriptionClient
import com.github.meetingrecorder.data.WhisperModelStore
import com.github.meetingrecorder.data.WhisperTranscriptionClient
import com.github.meetingrecorder.speakers.SpeakerModelStore
import com.github.meetingrecorder.speakers.SpeakerRepository
import com.github.meetingrecorder.speakers.SpeakerService
import com.github.meetingrecorder.speakers.SpeakerTranscriptionClient
import java.io.File

/**
 * Manual dependency container — the app is small enough that a hand-wired container is clearer
 * than a DI framework. Owned by [MeetingRecorderApp]; ViewModels receive these dependencies via
 * constructor injection through [appViewModelFactory] instead of casting `application`.
 */
class AppContainer(context: Context) {

    val config: Config = Config(context)
    val qwenModels = QwenModelStore(File(context.noBackupFilesDir, "qwen3-asr-model"))
    val whisperModels = WhisperModelStore(File(context.noBackupFilesDir, "whisper-models"))

    val speakerModels = SpeakerModelStore(File(context.noBackupFilesDir, "speaker-models"))
    val speakerRepository = SpeakerRepository(File(context.noBackupFilesDir, "speaker-data"))
    val meetingRepository: MeetingRepository = MeetingRepository(File(documentsDirectory(context), "Meetings"), speakerRepository::deleteMeetingSamples)
    val speakerService by lazy { SpeakerService(speakerModels, speakerRepository) }

    /** Shared Gemini client; it reads the latest key/model/prompts from [config] on every call. */
    val geminiClient: GeminiClient by lazy { GeminiClient(config) }

    private val selectedTranscription by lazy {
        SelectedTranscriptionClient(config, geminiClient, AliTranscriptionClient(config), WhisperTranscriptionClient(config, whisperModels), QwenTranscriptionClient(qwenModels))
    }
    private val speakerTranscription by lazy { SpeakerTranscriptionClient(speakerService, selectedTranscription) }
    private val configuredTranscription by lazy {
        object : TranscriptionClient {
            override suspend fun transcribe(audioFile: File, mimeType: String, onStatus: (String) -> Unit): String =
                if (config.speakerSeparationEnabled && speakerModels.installed()) speakerTranscription.transcribe(audioFile, mimeType, onStatus) else selectedTranscription.transcribe(audioFile, mimeType, onStatus)
        }
    }

    /** Shared processing workflow (transcribe → summarize → title → save) used by the record and detail flows. */
    val meetingProcessor: MeetingProcessor by lazy {
        MeetingProcessor(
            geminiClient,
            meetingRepository,
            summaryClient = SelectedSummaryClient(config, geminiClient, DeepSeekClient(config)),
            transcriptionClient = configuredTranscription,
            speakerTranscriptionClient = speakerTranscription,
        )
    }

    /**
     * Resolves the shared `Documents` directory on primary external storage
     * (`/storage/emulated/0/Documents`) without `Environment.getExternalStoragePublicDirectory()`
     * (docs-deprecated since API 29). `StorageVolume.getDirectory()` (API 30+; minSdk is 31)
     * reports the same root that call resolved against, so the on-disk location is unchanged; the
     * fallback covers the (rare) case where the primary volume reports no mounted directory.
     */
    private fun documentsDirectory(context: Context): File {
        val primaryRoot = context.getSystemService(StorageManager::class.java)
            ?.primaryStorageVolume
            ?.directory
            ?: Environment.getExternalStorageDirectory()
        return File(primaryRoot, Environment.DIRECTORY_DOCUMENTS)
    }
}
