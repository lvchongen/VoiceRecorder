package com.github.meetingrecorder.ui.detail

import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.meetingrecorder.audio.preferredRecording
import com.github.meetingrecorder.data.Config
import com.github.meetingrecorder.data.MeetingMeta
import com.github.meetingrecorder.data.MeetingProcessor
import com.github.meetingrecorder.data.SummaryOptions
import com.github.meetingrecorder.data.SummaryProvider
import com.github.meetingrecorder.data.SummaryVersion
import com.github.meetingrecorder.data.summaryOptions
import com.github.meetingrecorder.util.extensionToMimeType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Generation (transcribe / summarize) status for the detail screen. */
sealed interface GenState {
    data object Idle : GenState
    data class Processing(val status: String) : GenState
    data class Error(val msg: String) : GenState
}

// View model for the meeting detail screen. Plain ViewModel — no Context needed (MediaPlayer is
// context-free); dependencies are constructor-injected via appViewModelFactory. The generation
// workflows (transcribe/summarize/title/disk writes) live in MeetingProcessor; this class keeps
// only screen state and playback.
class MeetingDetailViewModel(
    private val config: Config,
    private val processor: MeetingProcessor,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _transcript = MutableStateFlow<String?>(null)
    val transcript: StateFlow<String?> = _transcript.asStateFlow()

    val defaultSummaryOptions get() = config.summaryOptions()
    private val _preview = MutableStateFlow<SummaryVersion?>(null)
    val preview = _preview.asStateFlow()
    private val _versions = MutableStateFlow<List<SummaryVersion>>(emptyList())
    val versions = _versions.asStateFlow()

    fun dismissPreview() {
        _preview.value = null
    }
    fun previewVersion(version: SummaryVersion) {
        if (_genState.value !is GenState.Processing) _preview.value = version
    }

    fun adoptPreview() {
        if (_genState.value is GenState.Processing) return
        val version = _preview.value ?: return
        val dir = meetingDir ?: return
        generationJob = viewModelScope.launch {
            _genState.value = GenState.Processing("正在保存纪要…")
            try {
                processor.adoptSummary(dir, version)
                _notes.value = version.notes
                _preview.value = null
                _versions.value = processor.summaryVersions(dir)
                maybeGenerateTitle(version.notes)
                processor.saveMetadata(dir, currentTitle, durationSeconds)
                _genState.value = GenState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _genState.value = GenState.Error(e.message ?: "保存失败")
            }
        }
    }

    private val _notes = MutableStateFlow<String?>(null)
    val notes: StateFlow<String?> = _notes.asStateFlow()

    private val _hasAudio = MutableStateFlow(false)
    val hasAudio: StateFlow<Boolean> = _hasAudio.asStateFlow()

    private val _genState = MutableStateFlow<GenState>(GenState.Idle)
    val genState: StateFlow<GenState> = _genState.asStateFlow()

    private val _hasOriginal = MutableStateFlow(false)
    val hasOriginal = _hasOriginal.asStateFlow()
    private val _usingOriginal = MutableStateFlow(false)
    val usingOriginal = _usingOriginal.asStateFlow()
    private val _audioWarning = MutableStateFlow<String?>(null)
    val audioWarning = _audioWarning.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentTime = MutableStateFlow("00:00")
    val currentTime: StateFlow<String> = _currentTime.asStateFlow()

    private val _totalTime = MutableStateFlow("00:00")
    val totalTime: StateFlow<String> = _totalTime.asStateFlow()

    private var playbackReady = false

    @Volatile private var mediaPlayer: MediaPlayer? = null
    private var timeUpdaterJob: Job? = null
    private var audioFile: File? = null

    // Generation context, read from meeting.json on load() and preserved on save.
    private var meetingDir: File? = null
    private var currentTitle: String? = null
    private var durationSeconds: Int = 0
    private var generationJob: Job? = null

    // Everything read off disk by load(), so file I/O happens on IO and the
    // ViewModel state assignments happen on the Main thread only.
    private data class LoadedMeeting(
        val transcript: String?,
        val notes: String?,
        val title: String?,
        val durationSeconds: Int,
        val audioFile: File?,
        val player: MediaPlayer?,
        val hasOriginal: Boolean,
        val warning: String?,
    )

    fun load(meetingPath: String) {
        // Cancel any in-flight generation from a previously loaded meeting so its background
        // writes can't clobber the meeting we're loading now.
        generationJob?.cancel()
        // Reset playback state from the previous meeting: without this, a meeting
        // that was playing leaves _isPlaying true and the timer running, so the
        // newly loaded meeting shows as "playing" on a player that never started.
        timeUpdaterJob?.cancel()
        timeUpdaterJob = null
        _isPlaying.value = false
        _currentTime.value = "00:00"
        val dir = File(meetingPath)
        meetingDir = dir
        _preview.value = null
        _versions.value = emptyList()
        _genState.value = GenState.Idle
        _transcript.value = null
        _notes.value = null
        generationJob = viewModelScope.launch {
            val loaded = withContext(ioDispatcher) {
                val transcript = File(dir, "transcript.md").takeIf { it.exists() }?.readText()
                val notes = File(dir, "notes.md").takeIf { it.exists() }?.readText()

                // Preserve existing title / duration so generation writes them back correctly.
                val meta = File(dir, "meeting.json").takeIf { it.exists() }?.let { metaFile ->
                    try {
                        MeetingMeta.parse(metaFile.readText())
                    } catch (_: Exception) {
                        null
                    }
                }

                val audio = preferredRecording(dir)
                val player = audio?.let {
                    MediaPlayer().apply {
                        setDataSource(it.absolutePath)
                        prepare()
                    }
                }
                LoadedMeeting(
                    transcript,
                    notes,
                    meta?.title,
                    meta?.durationSeconds ?: 0,
                    audio,
                    player,
                    audio?.name == "recording-denoised.m4a" && File(dir, "recording.m4a").exists(),
                    File(dir, "denoise-warning.txt").takeIf { it.exists() }?.readText(),
                )
            }

            _versions.value = processor.summaryVersions(dir)
            _transcript.value = loaded.transcript
            _notes.value = loaded.notes
            currentTitle = loaded.title
            durationSeconds = loaded.durationSeconds

            mediaPlayer?.release()
            mediaPlayer = loaded.player
            playbackReady = loaded.player != null
            audioFile = loaded.audioFile
            _hasOriginal.value = loaded.hasOriginal
            _usingOriginal.value = false
            _audioWarning.value = loaded.warning
            loaded.player?.setOnCompletionListener {
                _isPlaying.value = false
                _currentTime.value = "00:00"
                timeUpdaterJob?.cancel()
            }
            _totalTime.value = loaded.player?.let { formatDuration(it.duration.toLong()) } ?: "00:00"
            _hasAudio.value = loaded.player != null
        }
    }

    /** Persist the transcript, then stage notes for explicit adoption. Requires audio. */
    fun generateTranscriptAndNotes(options: SummaryOptions = defaultSummaryOptions, withSpeakers: Boolean = false) {
        if (_genState.value is GenState.Processing) return
        val dir = meetingDir ?: return
        val audio = audioFile ?: return
        config.processingError(needsTranscription = true)?.let {
            _genState.value = GenState.Error(it)
            return
        }
        generationJob = viewModelScope.launch {
            _genState.value = GenState.Processing("Starting…")
            try {
                val provider = config.summaryProvider
                val model = if (provider == SummaryProvider.DEEPSEEK) config.deepSeekModel else config.model
                val prompt = config.templatePrompt(options.template) + "\n\n全局补充要求：\n" + config.summarizationPrompt
                val result = processor.transcribeAndSummarize(
                    audio,
                    extensionToMimeType(audio.extension),
                    meetingDir = dir,
                    onTranscriptSaved = { _transcript.value = it },
                    summaryOptions = options,
                    saveNotesCheckpoint = false,
                    withSpeakers = withSpeakers,
                ) {
                    _genState.value = GenState.Processing(it)
                }
                _preview.value = SummaryVersion(notes = result.notes, sourceTranscript = result.transcript, options = options, provider = provider.name, model = model, prompt = prompt)
                _transcript.value = result.transcript
                _genState.value = GenState.Idle
            } catch (e: CancellationException) {
                throw e // a cancelled job (e.g. reload) must not surface as an error
            } catch (e: Exception) {
                _genState.value = GenState.Error(e.message ?: "Generation failed")
            }
        }
    }

    /** Generate notes when none exist yet, reusing the already-loaded transcript. */
    fun generateNotes() = runNotesGeneration()

    /** Reuse the transcript and stage a preview; adoption is an explicit separate action. */
    fun regenerateNotes(options: SummaryOptions = defaultSummaryOptions) = runNotesGeneration(options)

    private fun runNotesGeneration(options: SummaryOptions = defaultSummaryOptions) {
        if (_genState.value is GenState.Processing) return
        if (meetingDir == null) return
        val transcript = _transcript.value ?: return
        config.processingError(needsTranscription = false)?.let {
            _genState.value = GenState.Error(it)
            return
        }
        generationJob = viewModelScope.launch {
            _genState.value = GenState.Processing("Generating meeting notes…")
            try {
                val provider = config.summaryProvider
                val model = if (provider == SummaryProvider.DEEPSEEK) config.deepSeekModel else config.model
                val prompt = config.templatePrompt(options.template) + "\n\n全局补充要求：\n" + config.summarizationPrompt
                val notes = processor.previewSummary(transcript, options) { _genState.value = GenState.Processing(it) }
                check(notes.isNotBlank()) { "模型返回了空纪要，请重试。" }
                _preview.value = SummaryVersion(notes = notes, sourceTranscript = transcript, options = options, provider = provider.name, model = model, prompt = prompt)
                _genState.value = GenState.Idle
            } catch (e: CancellationException) {
                throw e // a cancelled job (e.g. reload) must not surface as an error
            } catch (e: Exception) {
                _genState.value = GenState.Error(e.message ?: "Generation failed")
            }
        }
    }

    /** Auto-generate a title when the meeting has none (best-effort, mirrors the main flow). */
    private suspend fun maybeGenerateTitle(notes: String) {
        if (currentTitle.isNullOrBlank()) {
            processor.generateTitle(notes)?.let { currentTitle = it }
        }
    }

    fun selectOriginal(original: Boolean) {
        if (!_hasOriginal.value || original == _usingOriginal.value) return
        val dir = meetingDir ?: return
        val file = if (original) File(dir, "recording.m4a") else audioFile ?: return
        timeUpdaterJob?.cancel()
        _isPlaying.value = false
        _currentTime.value = "00:00"
        mediaPlayer?.release()
        mediaPlayer = null
        _usingOriginal.value = original
        playbackReady = false
        val player = MediaPlayer()
        try {
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener {
                mediaPlayer = it
                playbackReady = true
                _totalTime.value = formatDuration(it.duration.toLong())
            }
            player.setOnCompletionListener {
                _isPlaying.value = false
                _currentTime.value = "00:00"
                timeUpdaterJob?.cancel()
            }
            player.setOnErrorListener { _, _, _ ->
                _audioWarning.value = "音频播放失败，请重新打开会议"
                true
            }
            mediaPlayer = player
            player.prepareAsync()
        } catch (e: Exception) {
            player.release()
            mediaPlayer = null
            _audioWarning.value = "音频播放失败：${e.message}"
        }
    }

    fun playPause() {
        if (!playbackReady) return
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                _isPlaying.value = false
                timeUpdaterJob?.cancel()
            } else {
                it.start()
                _isPlaying.value = true
                startTimeUpdater()
            }
        }
    }

    fun stop() {
        if (!playbackReady) return
        mediaPlayer?.let {
            it.pause()
            it.seekTo(0)
            _isPlaying.value = false
            _currentTime.value = "00:00"
            timeUpdaterJob?.cancel()
        }
    }

    private fun startTimeUpdater() {
        timeUpdaterJob = viewModelScope.launch {
            while (_isPlaying.value) {
                mediaPlayer?.let {
                    try {
                        _currentTime.value = formatDuration(it.currentPosition.toLong())
                    } catch (e: IllegalStateException) {
                        // Player might have been released
                    }
                }
                delay(1000)
            }
        }
    }

    private fun formatDuration(millis: Long): String {
        return String.format(
            "%02d:%02d",
            TimeUnit.MILLISECONDS.toMinutes(millis),
            TimeUnit.MILLISECONDS.toSeconds(millis) -
                TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis)),
        )
    }

    override fun onCleared() {
        super.onCleared()
        mediaPlayer?.release()
        mediaPlayer = null
        timeUpdaterJob?.cancel()
    }
}
