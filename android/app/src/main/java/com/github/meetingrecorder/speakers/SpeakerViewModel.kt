package com.github.meetingrecorder.speakers

import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.meetingrecorder.data.Config
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class SpeakerUiState(
    val meeting: SpeakerMeeting? = null,
    val profiles: List<VoiceProfile> = emptyList(),
    val modelsReady: Boolean = false,
    val busy: Boolean = false,
    val message: String = "",
    val error: String? = null,
    val playingSample: String? = null,
)

class SpeakerViewModel(
    private val service: SpeakerService,
    private val repository: SpeakerRepository,
    private val config: Config,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(SpeakerUiState())
    val state = _state.asStateFlow()
    private var meetingDir: File? = null
    private var job: Job? = null
    private var player: MediaPlayer? = null
    private var profileObserver: Job? = null
    fun load(path: String? = null) {
        if (_state.value.busy && meetingDir?.absolutePath == path && job?.isActive == true) return
        job?.cancel()
        profileObserver?.cancel()
        stopSample()
        meetingDir = path?.let(::File)
        val dir = meetingDir
        _state.value = SpeakerUiState(busy = true)
        job = viewModelScope.launch {
            try {
                val meeting = withContext(ioDispatcher) { dir?.let(repository::readMeeting) }
                val profiles = withContext(ioDispatcher) { repository.profiles() }
                _state.value = SpeakerUiState(meeting, profiles, service.models.installed())
                profileObserver = viewModelScope.launch {
                    repository.profileChanges.collect {
                        val latest = withContext(ioDispatcher) { repository.profiles() }
                        _state.update { it.copy(profiles = latest, modelsReady = service.models.installed()) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.message, modelsReady = service.models.installed()) }
            }
        }
    }

    private fun action(message: String, block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = message, error = null) }
        job = viewModelScope.launch {
            try {
                block()
                val profiles = withContext(ioDispatcher) { repository.profiles() }
                _state.update { it.copy(profiles = profiles, modelsReady = service.models.installed(), busy = false, message = "") }
            } catch (e: CancellationException) {
                _state.update { it.copy(busy = false, message = "已取消") }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.message ?: "操作失败", message = "") }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun downloadModels() = action("正在下载分人模型…") {
        service.models.download { percent -> _state.update { it.copy(message = "正在下载分人模型：$percent%") } }
    }

    fun deleteModels() = action("正在删除模型…") { service.models.delete() }

    /** Takes ownership of the dialog's private temporary recording, including cancellation cleanup. */
    fun updateRecordedVoiceSample(audio: File, profileId: String) {
        if (_state.value.busy) {
            audio.parentFile?.deleteRecursively()
            return
        }
        importVoiceSample(requireNotNull(audio.parentFile?.parentFile), { audio.inputStream() }, profileId)
        job?.invokeOnCompletion { audio.parentFile?.deleteRecursively() }
    }

    fun importVoiceSample(cacheDir: File, openStream: () -> java.io.InputStream?, profileId: String?) {
        stopSample()
        action("正在更新声纹…") {
            withContext(ioDispatcher) {
                val directory = java.nio.file.Files.createTempDirectory(cacheDir.toPath(), "voice-import-").toFile()
                try {
                    val audio = File(directory, "recording.m4a")
                    openStream()?.use { input -> audio.outputStream().use(input::copyTo) } ?: error("无法读取所选录音")
                    if (!service.models.installed()) service.models.download {}
                    val meeting = service.analyze(audio, directory)
                    check(meeting.speakers.size == 1 && meeting.speakers.single().samples.isNotEmpty()) { "请提供只包含这位说话人的清晰录音" }
                    if (profileId == null) {
                        repository.autoEnroll(directory)
                    } else {
                        val profile = repository.profiles().firstOrNull { it.id == profileId } ?: error("声纹已删除")
                        val speaker = meeting.speakers.single()
                        val sampleIds = speaker.samples.take((20 - profile.samples.size).coerceAtLeast(0)).map { it.id }.toSet()
                        check(sampleIds.isNotEmpty()) { "样本已满，请先移除旧样本" }
                        repository.confirm(directory, speaker.label, profile.name, profile.id, true, sampleIds)
                    }
                } finally {
                    repository.deleteMeetingSamples(directory)
                    directory.deleteRecursively()
                }
            }
        }
    }

    fun renameProfile(id: String, name: String) = action("正在修改姓名…") {
        withContext(ioDispatcher) { repository.renameProfile(id, name) }
    }

    fun deleteProfile(id: String) {
        stopSample()
        action("正在删除声纹…") { withContext(ioDispatcher) { repository.deleteProfile(id) } }
    }

    fun removeSample(profileId: String, sampleId: String) {
        stopSample()
        action("正在移除样本…") { withContext(ioDispatcher) { repository.removeSample(profileId, sampleId) } }
    }

    fun playMeetingSample(label: String, sampleId: String) {
        val meeting = _state.value.meeting ?: return
        val sample = meeting.speakers.firstOrNull { it.label == label }?.samples?.firstOrNull { it.id == sampleId } ?: return
        play(repository.meetingSample(meeting, sample), sample.id)
    }

    fun playProfileSample(profileId: String, sampleId: String) {
        val profile = _state.value.profiles.firstOrNull { it.id == profileId } ?: return
        val sample = profile.samples.firstOrNull { it.id == sampleId } ?: return
        play(repository.profileSample(profile, sample), sample.id)
    }

    private fun play(file: File, id: String) {
        val previous = _state.value.playingSample
        stopSample()
        if (previous == id) return
        val media = MediaPlayer()
        player = media
        try {
            media.setDataSource(file.absolutePath)
            media.setOnPreparedListener { if (player === it) it.start() }
            media.setOnCompletionListener { if (player === it) stopSample() }
            media.setOnErrorListener { failed, _, _ ->
                if (player === failed) {
                    stopSample()
                    _state.update { it.copy(error = "样本播放失败") }
                }
                true
            }
            _state.update { it.copy(playingSample = id) }
            media.prepareAsync()
        } catch (e: Exception) {
            stopSample()
            _state.update { it.copy(error = e.message ?: "样本播放失败") }
        }
    }

    fun stopSample() {
        val previous = player
        player = null
        previous?.release()
        _state.update { it.copy(playingSample = null) }
    }

    override fun onCleared() {
        stopSample()
    }
}
