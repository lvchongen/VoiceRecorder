package com.github.meetingrecorder.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.meetingrecorder.data.AudioQuality
import com.github.meetingrecorder.data.Config
import com.github.meetingrecorder.data.QwenModelStore
import com.github.meetingrecorder.data.SummaryProvider
import com.github.meetingrecorder.data.ThemeMode
import com.github.meetingrecorder.data.TranscriptionProvider
import com.github.meetingrecorder.data.WhisperModel
import com.github.meetingrecorder.data.WhisperModelStore
import com.github.meetingrecorder.data.summaryOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// Plain ViewModel — no Context needed; Config is constructor-injected via appViewModelFactory.
class SettingsViewModel(private val config: Config, private val models: WhisperModelStore, private val qwenModels: QwenModelStore, private val localModels: com.github.meetingrecorder.data.LocalSummaryModelStore) : ViewModel() {

    val localSummaryModel = MutableStateFlow(config.localSummaryModel)
    val installedSummaryModels = MutableStateFlow(com.github.meetingrecorder.data.LocalSummaryModel.all.filter { localModels.installed(it) }.map { it.id }.toSet())
    val summaryModelStatus = MutableStateFlow("")
    val summaryModelBusy = MutableStateFlow(false)
    private var summaryModelJob: Job? = null
    fun setLocalSummaryModel(id: String) {
        config.localSummaryModel = id
        localSummaryModel.value = id
    }
    fun downloadSummaryModel(model: com.github.meetingrecorder.data.LocalSummaryModel) = summaryModelAction {
        localModels.download(model) { summaryModelStatus.value = "${model.label}：$it%" }
        summaryModelStatus.value = "${model.label} 下载完成，校验通过"
    }
    fun deleteSummaryModel(model: com.github.meetingrecorder.data.LocalSummaryModel) = summaryModelAction {
        localModels.delete(model)
        summaryModelStatus.value = "${model.label} 已删除"
    }
    fun cancelSummaryModelDownload() {
        summaryModelJob?.cancel()
    }
    private fun summaryModelAction(action: suspend () -> Unit) {
        if (summaryModelBusy.value) return
        summaryModelBusy.value = true
        summaryModelStatus.value = "准备中…"
        summaryModelJob = viewModelScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                summaryModelStatus.value = "已取消"
                throw e
            } catch (e: Exception) {
                summaryModelStatus.value = e.message ?: "模型操作失败"
            } finally {
                installedSummaryModels.value = com.github.meetingrecorder.data.LocalSummaryModel.all.filter { localModels.installed(it) }.map { it.id }.toSet()
                summaryModelBusy.value = false
            }
        }
    }

    val qwenInstalled = MutableStateFlow(qwenModels.installed())
    fun downloadQwen() = modelAction {
        qwenModels.download { modelStatus.value = "Qwen3-ASR：$it%" }
        modelStatus.value = "Qwen3-ASR 下载完成"
    }
    fun deleteQwen() = modelAction {
        qwenModels.delete()
        modelStatus.value = "Qwen3-ASR 已删除"
    }

    val denoiseModel = MutableStateFlow(config.denoiseModel)
    fun setDenoiseModel(value: com.github.meetingrecorder.data.DenoiseModel) {
        config.denoiseModel = value
        denoiseModel.value = value
    }

    val recordingDenoiseEnabled = MutableStateFlow(config.recordingDenoiseEnabled)
    fun setRecordingDenoiseEnabled(value: Boolean) {
        config.recordingDenoiseEnabled = value
        recordingDenoiseEnabled.value = value
    }

    val themeMode = config.themeMode
    fun setThemeMode(mode: ThemeMode) = config.setThemeMode(mode)

    val whisperModel = MutableStateFlow(config.whisperModel)
    val modelStatus = MutableStateFlow("")
    val modelBusy = MutableStateFlow(false)
    val installedModels = MutableStateFlow(WhisperModel.all.filter { models.installed(it) }.map { it.id }.toSet())
    private var modelJob: Job? = null
    fun setWhisperModel(value: String) {
        config.whisperModel = value
        whisperModel.value = value
    }
    fun downloadModel(model: WhisperModel) = modelAction {
        models.download(model) { modelStatus.value = "${model.id}: $it%" }
        modelStatus.value = "${model.id} 下载完成，校验通过"
    }
    fun deleteModel(model: WhisperModel) = modelAction {
        models.delete(model)
        modelStatus.value = "${model.id} 已删除"
    }
    fun cancelModelDownload() {
        modelJob?.cancel()
    }
    private fun modelAction(action: suspend () -> Unit) {
        if (modelBusy.value) return
        modelBusy.value = true
        modelStatus.value = "准备中…"
        modelJob = viewModelScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                modelStatus.value = "已取消"
                throw e
            } catch (e: Exception) {
                modelStatus.value = e.message ?: "模型操作失败"
            } finally {
                qwenInstalled.value = qwenModels.installed()
                installedModels.value = WhisperModel.all.filter { models.installed(it) }.map { it.id }.toSet()
                modelBusy.value = false
            }
        }
    }

    private val _transcriptionProvider = MutableStateFlow(config.transcriptionProvider)
    val transcriptionProvider = _transcriptionProvider.asStateFlow()
    fun setTranscriptionProvider(value: TranscriptionProvider) {
        config.transcriptionProvider = value
        _transcriptionProvider.value = value
    }

    private val _aliApiKey = MutableStateFlow(config.aliApiKey)
    val aliApiKey = _aliApiKey.asStateFlow()
    fun setAliApiKey(value: String) {
        config.aliApiKey = value
        _aliApiKey.value = value
    }

    private val _aliBaseUrl = MutableStateFlow(config.aliBaseUrl)
    val aliBaseUrl = _aliBaseUrl.asStateFlow()
    fun setAliBaseUrl(value: String) {
        config.aliBaseUrl = value
        _aliBaseUrl.value = value
    }

    private val _aliModel = MutableStateFlow(config.aliModel)
    val aliModel = _aliModel.asStateFlow()
    fun setAliModel(value: String) {
        config.aliModel = value
        _aliModel.value = value
    }

    val summaryOptions get() = config.summaryOptions()
    fun setSummaryOptions(value: com.github.meetingrecorder.data.SummaryOptions) {
        config.summaryTemplate = value.template
        config.summaryDetail = value.detail
    }

    private val _summaryLanguage = MutableStateFlow(config.summaryLanguage)
    val summaryLanguage = _summaryLanguage.asStateFlow()
    fun setSummaryLanguage(value: String) {
        config.summaryLanguage = value
        _summaryLanguage.value = value
    }

    private val _summaryProvider = MutableStateFlow(config.summaryProvider)
    val summaryProvider = _summaryProvider.asStateFlow()
    private val _deepSeekApiKey = MutableStateFlow(config.deepSeekApiKey)
    val deepSeekApiKey = _deepSeekApiKey.asStateFlow()
    private val _deepSeekModel = MutableStateFlow(config.deepSeekModel)
    val deepSeekModel = _deepSeekModel.asStateFlow()

    fun setSummaryProvider(value: SummaryProvider) {
        config.summaryProvider = value
        _summaryProvider.value = value
    }

    fun setDeepSeekApiKey(value: String) {
        config.deepSeekApiKey = value.trim()
        _deepSeekApiKey.value = value.trim()
    }

    fun setDeepSeekModel(value: String) {
        config.deepSeekModel = value.trim()
        _deepSeekModel.value = value.trim()
    }

    private val _apiKey = MutableStateFlow(config.apiKey)
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _model = MutableStateFlow(config.model)
    val model: StateFlow<String> = _model.asStateFlow()

    val availableModels: List<String> = Config.AVAILABLE_MODELS

    private val _audioQuality = MutableStateFlow(config.audioQuality)
    val audioQuality: StateFlow<AudioQuality> = _audioQuality.asStateFlow()

    val availableQualities: List<AudioQuality> = AudioQuality.entries

    private val _transcriptionPrompt = MutableStateFlow(config.transcriptionPrompt)
    val transcriptionPrompt: StateFlow<String> = _transcriptionPrompt.asStateFlow()

    private val _summarizationPrompt = MutableStateFlow(config.summarizationPrompt)
    val summarizationPrompt: StateFlow<String> = _summarizationPrompt.asStateFlow()

    private val _titlePrompt = MutableStateFlow(config.titlePrompt)
    val titlePrompt: StateFlow<String> = _titlePrompt.asStateFlow()

    private val _dndDuringRecordingEnabled = MutableStateFlow(config.dndDuringRecordingEnabled)
    val dndDuringRecordingEnabled: StateFlow<Boolean> = _dndDuringRecordingEnabled.asStateFlow()

    fun setApiKey(key: String) {
        _apiKey.value = key
        config.apiKey = key
    }

    fun setModel(model: String) {
        _model.value = model
        config.model = model
    }

    fun setAudioQuality(quality: AudioQuality) {
        _audioQuality.value = quality
        config.audioQuality = quality
    }

    fun setTranscriptionPrompt(value: String) {
        _transcriptionPrompt.value = value
        config.transcriptionPrompt = value
    }

    fun templatePrompt(template: com.github.meetingrecorder.data.MeetingTemplate) = config.templatePrompt(template)

    fun setTemplatePrompt(template: com.github.meetingrecorder.data.MeetingTemplate, value: String) = config.setTemplatePrompt(template, value)

    fun setSummarizationPrompt(value: String) {
        _summarizationPrompt.value = value
        config.summarizationPrompt = value
    }

    fun setTitlePrompt(value: String) {
        _titlePrompt.value = value
        config.titlePrompt = value
    }

    fun setDndDuringRecordingEnabled(enabled: Boolean) {
        _dndDuringRecordingEnabled.value = enabled
        config.dndDuringRecordingEnabled = enabled
    }
}
