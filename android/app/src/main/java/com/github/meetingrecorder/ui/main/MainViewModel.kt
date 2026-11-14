package com.github.meetingrecorder.ui.main

import android.app.Application
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.meetingrecorder.R
import com.github.meetingrecorder.audio.RecordingPhase
import com.github.meetingrecorder.audio.RecordingService
import com.github.meetingrecorder.data.Config
import com.github.meetingrecorder.data.MeetingMeta
import com.github.meetingrecorder.data.MeetingProcessor
import com.github.meetingrecorder.data.MeetingRepository
import com.github.meetingrecorder.util.extensionToMimeType
import com.github.meetingrecorder.util.normalizeMimeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class RecordingState {
    data object Ready : RecordingState()
    data class Recording(val elapsedSecs: Int) : RecordingState()
    data object AudioSaved : RecordingState()
    data class Processing(val statusMsg: String) : RecordingState()
    data class Done(val transcript: String, val notes: String) : RecordingState()
    data class Error(val msg: String) : RecordingState()
}

private const val TAG = "MainViewModel"

// AndroidViewModel because it genuinely needs a Context: starting/stopping RecordingService,
// getString, and contentResolver. Everything else is constructor-injected via appViewModelFactory.
class MainViewModel(
    application: Application,
    private val config: Config,
    private val meetingRepository: MeetingRepository,
    private val processor: MeetingProcessor,
) : AndroidViewModel(application) {

    private val app: Application = application

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Ready)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private var timerJob: Job? = null
    private var currentMeetingDir: File? = null
    val savedMeetingPath: String? get() = currentMeetingDir?.absolutePath
    private var lockFile: File? = null
    private var currentTitle: String? = null
    private var durationSeconds: Int = 0

    // True when processing a file that already lives inside a meeting directory (no copy was made).
    // discardResults() must not delete the directory in this case.
    private var isInPlace: Boolean = false

    fun configurationError(): String? = config.processingError(needsTranscription = true)

    fun hasApiKey(): Boolean = configurationError() == null

    fun startRecording(title: String?) {
        currentTitle = title
        durationSeconds = 0

        viewModelScope.launch {
            // Do the blocking I/O on IO, but assign ViewModel state on Main only
            // (these properties are read/written on the Main thread elsewhere).
            val (meetingDir, lock) = try {
                withContext(Dispatchers.IO) {
                    val dir = meetingRepository.createMeetingDir(title)
                    dir to File(dir, ".recording").also { it.createNewFile() }
                }
            } catch (e: Exception) {
                _state.value = RecordingState.Error("Could not create recording folder: ${e.message}")
                return@launch
            }
            lockFile = lock
            currentMeetingDir = meetingDir

            try {
                _state.value = RecordingState.Processing("正在启动录音…")
                RecordingService.start(app, meetingDir, config.audioQuality.bitrate)
                val started = RecordingService.status.first {
                    it.phase == RecordingPhase.RECORDING || it.phase == RecordingPhase.FAILED
                }
                check(started.phase == RecordingPhase.RECORDING) { "无法启动录音，请检查麦克风权限" }
            } catch (e: Exception) {
                lockFile = null
                currentMeetingDir = null
                withContext(Dispatchers.IO) {
                    lock.delete()
                    meetingDir.deleteRecursively()
                }
                _state.value = RecordingState.Error("Could not start recording: ${e.message}")
                return@launch
            }

            _state.value = RecordingState.Recording(0)
            timerJob = viewModelScope.launch {
                while (true) {
                    delay(1_000)
                    durationSeconds++
                    _state.value = RecordingState.Recording(durationSeconds)
                }
            }
        }
    }

    fun stopRecording() {
        timerJob?.cancel()
        timerJob = null

        val meetingDir = currentMeetingDir ?: run {
            _state.value = RecordingState.Error("No meeting directory")
            return
        }

        _state.value = RecordingState.Processing("正在保存录音…")
        viewModelScope.launch {
            // The recorder lives in the foreground service; stopping is asynchronous, so wait for
            // the service to finish writing the file and report whether the mic was silenced.
            RecordingService.stop(app)
            val status = RecordingService.status.first {
                it.phase == RecordingPhase.STOPPED || it.phase == RecordingPhase.FAILED
            }

            val audioFile = status.outputPath?.let(::File) ?: File(meetingDir, "recording.m4a")
            val outcome = withContext(Dispatchers.IO) {
                decideStopOutcome(
                    phase = status.phase,
                    fileExists = audioFile.exists(),
                    fileLength = audioFile.length(),
                    silenced = status.silenced,
                )
            }
            when (outcome) {
                StopOutcome.FILE_NOT_FOUND ->
                    _state.value = RecordingState.Error("Recording file not found. Check storage permission.")
                StopOutcome.EMPTY ->
                    _state.value = RecordingState.Error(
                        "Recording is empty (0 bytes). " +
                            "Grant 'All files access' in Settings → Apps → Meeting Recorder → Permissions.",
                    )
                StopOutcome.SILENCED -> {
                    saveAudioOnlyAfterFailure()
                    _state.value = RecordingState.Error(app.getString(R.string.error_recording_silenced))
                }
                StopOutcome.SAVE_AUDIO -> try {
                    processor.saveAudioOnly(meetingDir, currentTitle, durationSeconds)
                    lockFile = null
                    _state.value = RecordingState.AudioSaved
                } catch (e: Exception) {
                    _state.value = RecordingState.Error("录音保存失败：${e.message}")
                }
            }
        }
    }

    /**
     * Transcription/summarization of a freshly-recorded meeting failed. Keep the audio discoverable
     * instead of orphaning it: write an audio-only meeting.json and remove the `.recording` lock so
     * the recording appears in the library and can be re-transcribed later via "Use Existing
     * Recording". Preserves all completed files. No-ops for in-place processing (no
     * lock was taken — the original audio-only meeting is already visible and untouched).
     */
    private suspend fun saveAudioOnlyAfterFailure() {
        val lock = lockFile ?: return
        val meetingDir = currentMeetingDir ?: return
        val title = currentTitle
        val duration = durationSeconds
        lockFile = null
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                meetingRepository.saveMeetingMeta(meetingDir, title, duration)
            } catch (_: Exception) {
                // Best-effort: even if the meta write fails, removing the lock below keeps the audio visible.
            }
            lock.delete()
        }
    }

    fun processExistingRecording(uri: Uri) {
        if (!hasApiKey()) {
            _state.value = RecordingState.Error(configurationError() ?: "Check AI settings.")
            return
        }

        // If the URI resolves to a file that already lives inside a meeting directory,
        // process it in-place — no copy, no new directory. meetingDirContaining walks
        // the meeting tree, so resolve on the IO dispatcher.
        viewModelScope.launch {
            val resolvedFile = resolveUriToFile(uri)
            val existingDir = resolvedFile?.let {
                withContext(Dispatchers.IO) { meetingRepository.meetingDirContaining(it) }
            }
            if (resolvedFile != null && existingDir != null) {
                processInPlace(existingDir, resolvedFile)
            } else {
                copyAndProcess(uri)
            }
        }
    }

    private fun processInPlace(meetingDir: File, audioFile: File) {
        currentMeetingDir = meetingDir
        isInPlace = true

        // No lock file: the meeting remains visible in the list (with audio only) while
        // processing runs. If processing fails the original audio-only meeting is unaffected.
        lockFile = null
        viewModelScope.launch {
            // Preserve existing title and duration so saveResults() writes them back
            // correctly. Parse on IO; assign ViewModel state on Main.
            val meta = withContext(Dispatchers.IO) {
                val metaFile = File(meetingDir, "meeting.json")
                if (metaFile.exists()) {
                    try {
                        MeetingMeta.parse(metaFile.readText())
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not read meeting.json in ${meetingDir.name}; proceeding without title/duration", e)
                        null
                    }
                } else {
                    null
                }
            }
            currentTitle = meta?.title
            durationSeconds = meta?.durationSeconds ?: 0
            try {
                processRecording(audioFile, extensionToMimeType(audioFile.extension))
            } catch (e: Exception) {
                _state.value = RecordingState.Error(e.message ?: "Processing failed")
            }
        }
    }

    private fun copyAndProcess(uri: Uri) {
        currentTitle = null
        durationSeconds = 0
        isInPlace = false

        viewModelScope.launch {
            var copied = false
            var importedDir: File? = null
            try {
                // Directory creation and lock file are blocking I/O — run on IO dispatcher.
                val (meetingDir, lf) = withContext(Dispatchers.IO) {
                    val dir = meetingRepository.createMeetingDir(null)
                    val lf = File(dir, ".recording").also { it.createNewFile() }
                    dir to lf
                }
                importedDir = meetingDir
                currentMeetingDir = meetingDir
                lockFile = lf

                _state.value = RecordingState.Processing(app.getString(R.string.status_copying_audio))

                // Always store as recording.m4a so listMeetings() hasAudio detection works.
                // The correct MIME type is still passed to Gemini for upload.
                val mimeType = normalizeMimeType(app.contentResolver.getType(uri) ?: "audio/mp4")
                val audioFile = withContext(Dispatchers.IO) {
                    val dest = File(meetingDir, "recording.m4a")
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw RuntimeException("Could not open selected file")
                    if (dest.length() == 0L) throw RuntimeException("Selected file is empty")
                    dest
                }
                copied = true
                processRecording(audioFile, mimeType)
            } catch (e: Exception) {
                if (copied) {
                    saveAudioOnlyAfterFailure()
                } else {
                    withContext(NonCancellable + Dispatchers.IO) { importedDir?.deleteRecursively() }
                    lockFile = null
                    currentMeetingDir = null
                }
                _state.value = RecordingState.Error(
                    if (copied) {
                        app.getString(R.string.error_processing_failed_audio_kept, e.message ?: "未知错误")
                    } else {
                        e.message ?: "Failed to import recording"
                    },
                )
            }
        }
    }

    /** Resolves a content/file URI to a File on primary external storage, or null if not possible. */
    private fun resolveUriToFile(uri: Uri): File? {
        if (uri.scheme == "file") return File(uri.path ?: return null)
        if (uri.scheme == "content" &&
            uri.authority == "com.android.externalstorage.documents"
        ) {
            val docId = DocumentsContract.getDocumentId(uri)
            val parts = docId.split(":")
            if (parts.size == 2 && parts[0] == "primary") {
                return File(Environment.getExternalStorageDirectory(), parts[1])
            }
        }
        return null
    }

    private suspend fun processRecording(audioFile: File, mimeType: String = "audio/mp4") {
        val result = processor.transcribeAndSummarize(
            audioFile,
            mimeType,
            meetingDir = requireNotNull(currentMeetingDir),
        ) { status ->
            _state.value = RecordingState.Processing(status)
        }

        // Auto-generate title when none was provided (best-effort; null on failure keeps it untitled)
        if (currentTitle == null) {
            currentTitle = processor.generateTitle(result.notes)
        }

        _state.value = RecordingState.Done(result.transcript, result.notes)
    }

    fun saveResults(onSaved: (String) -> Unit = {}) {
        val done = _state.value as? RecordingState.Done ?: return
        val meetingDir = currentMeetingDir ?: return

        viewModelScope.launch {
            try {
                val lock = lockFile
                processor.saveResults(meetingDir, done.transcript, done.notes, currentTitle, durationSeconds)
                withContext(Dispatchers.IO) { lock?.delete() }
                lockFile = null
                isInPlace = false
                _state.value = RecordingState.Ready
                onSaved(meetingDir.absolutePath)
            } catch (e: Exception) {
                _state.value = RecordingState.Error("Save failed: ${e.message}")
            }
        }
    }

    fun discardResults() {
        val lock = lockFile
        val dir = currentMeetingDir
        val deleteDir = !isInPlace
        lockFile = null
        // In-place: leave the existing meeting directory untouched (audio is still there).
        isInPlace = false
        currentMeetingDir = null
        _state.value = RecordingState.Ready
        viewModelScope.launch(Dispatchers.IO) {
            lock?.delete()
            if (deleteDir) {
                // New recording or copied import — remove the directory we created.
                dir?.deleteRecursively()
            }
        }
    }

    fun dismissError() {
        isInPlace = false
        _state.value = RecordingState.Ready
    }
}
