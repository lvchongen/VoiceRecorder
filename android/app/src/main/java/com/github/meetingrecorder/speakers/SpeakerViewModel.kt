package com.github.meetingrecorder.speakers

import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.meetingrecorder.audio.preferredRecording
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
    val separationEnabled get() = config.speakerSeparationEnabled

    fun setSeparationEnabled(enabled: Boolean) {
        config.speakerSeparationEnabled = enabled
    }

    fun load(path: String? = null) {
        if (_state.value.busy && meetingDir?.absolutePath == path && job?.isActive == true) return
        job?.cancel()
        stopSample()
        meetingDir = path?.let(::File)
        val dir = meetingDir
        _state.value = SpeakerUiState(busy = true)
        job = viewModelScope.launch {
            try {
                val meeting = withContext(ioDispatcher) { dir?.let(repository::readMeeting) }
                val profiles = withContext(ioDispatcher) { repository.profiles() }
                _state.value = SpeakerUiState(meeting, profiles, service.models.installed())
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

    fun analyze() {
        val dir = meetingDir ?: return
        stopSample()
        action("正在分析说话人…") {
            val audio = withContext(ioDispatcher) { preferredRecording(dir) ?: error("没有可分析的录音") }
            val meeting = service.analyze(audio, dir) { status -> _state.update { it.copy(message = status) } }
            _state.update { it.copy(meeting = meeting) }
        }
    }

    fun clearAnalysis() {
        val dir = meetingDir ?: return
        stopSample()
        action("正在清除本场分析…") {
            withContext(ioDispatcher) { repository.clearMeeting(dir) }
            _state.update { it.copy(meeting = null) }
        }
    }

    fun confirm(label: String, name: String, profileId: String?, saveVoiceprint: Boolean, sampleIds: Set<String>? = null) {
        val dir = meetingDir ?: return
        action("正在保存命名…") {
            val meeting = withContext(ioDispatcher) { repository.confirm(dir, label, name, profileId, saveVoiceprint, sampleIds) }
            _state.update { it.copy(meeting = meeting) }
        }
    }

    fun clearName(label: String) {
        val dir = meetingDir ?: return
        action("正在解除关联…") {
            val meeting = withContext(ioDispatcher) { repository.clearName(dir, label) }
            _state.update { it.copy(meeting = meeting) }
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
