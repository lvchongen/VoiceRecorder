package com.github.meetingrecorder.ui.settings

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.meetingrecorder.R
import com.github.meetingrecorder.appViewModelFactory
import com.github.meetingrecorder.data.Config
import com.github.meetingrecorder.data.QwenModelStore
import com.github.meetingrecorder.data.SummaryProvider
import com.github.meetingrecorder.data.ThemeMode
import com.github.meetingrecorder.data.TranscriptionProvider
import com.github.meetingrecorder.data.WhisperModel
import com.github.meetingrecorder.speakers.VoiceprintPanel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = appViewModelFactory),
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.tab_general)) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.tab_prompts)) },
                )
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("声纹") })
            }

            when (selectedTab) {
                0 -> GeneralTab(viewModel)
                1 -> PromptsTab(viewModel)
                2 -> VoiceprintPanel()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeneralTab(viewModel: SettingsViewModel) {
    val qwenInstalled by viewModel.qwenInstalled.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val whisperModel by viewModel.whisperModel.collectAsState()
    var whisperDraft by remember { mutableStateOf(whisperModel) }
    val modelStatus by viewModel.modelStatus.collectAsState()
    val modelBusy by viewModel.modelBusy.collectAsState()
    val installedModels by viewModel.installedModels.collectAsState()
    val transcriptionProvider by viewModel.transcriptionProvider.collectAsState()
    var transcriptionProviderDraft by remember { mutableStateOf(transcriptionProvider) }
    val aliApiKey by viewModel.aliApiKey.collectAsState()
    var aliApiKeyDraft by remember { mutableStateOf(aliApiKey) }
    val aliBaseUrl by viewModel.aliBaseUrl.collectAsState()
    var aliBaseUrlDraft by remember { mutableStateOf(aliBaseUrl) }
    val aliModel by viewModel.aliModel.collectAsState()
    var aliModelDraft by remember { mutableStateOf(aliModel) }
    var summaryOptionsDraft by remember { mutableStateOf(viewModel.summaryOptions) }
    val summaryLanguage by viewModel.summaryLanguage.collectAsState()
    var summaryLanguageDraft by remember { mutableStateOf(summaryLanguage) }
    val summaryProvider by viewModel.summaryProvider.collectAsState()
    val deepSeekApiKey by viewModel.deepSeekApiKey.collectAsState()
    val deepSeekModel by viewModel.deepSeekModel.collectAsState()
    var providerDraft by rememberSaveable { mutableStateOf(summaryProvider) }
    var deepSeekKeyDraft by remember { mutableStateOf(deepSeekApiKey) }
    var deepSeekModelDraft by rememberSaveable { mutableStateOf(deepSeekModel) }
    var providerMenuExpanded by remember { mutableStateOf(false) }
    val apiKey by viewModel.apiKey.collectAsState()
    val model by viewModel.model.collectAsState()
    val recordingDenoise by viewModel.recordingDenoiseEnabled.collectAsState()
    var recordingDenoiseDraft by rememberSaveable { mutableStateOf(recordingDenoise) }
    val audioQuality by viewModel.audioQuality.collectAsState()
    val dndDuringRecordingEnabled by viewModel.dndDuringRecordingEnabled.collectAsState()

    var apiKeyDraft by rememberSaveable { mutableStateOf(apiKey) }
    var modelDraft by rememberSaveable { mutableStateOf(model) }
    var audioQualityDraft by rememberSaveable { mutableStateOf(audioQuality) }
    var dndDuringRecordingDraft by rememberSaveable { mutableStateOf(dndDuringRecordingEnabled) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var qualityMenuExpanded by remember { mutableStateOf(false) }

    val context = LocalContext.current
    // Opening DND-access settings is a no-op contract — the service re-checks the grant at record time.
    val dndAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { /* nothing to do on return */ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("外观", style = MaterialTheme.typography.titleMedium)
        Text("选择后立即生效并保存", style = MaterialTheme.typography.bodySmall)
        ThemeMode.entries.forEach { mode ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = themeMode == mode, onClick = { viewModel.setThemeMode(mode) })
                TextButton(onClick = { viewModel.setThemeMode(mode) }) { Text(mode.label) }
            }
        }
        HorizontalDivider()
        Text("转写服务", style = MaterialTheme.typography.titleMedium)
        TranscriptionProvider.entries.filter { it != TranscriptionProvider.QWEN }.forEach { provider ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = transcriptionProviderDraft == provider, onClick = { transcriptionProviderDraft = provider })
                Text(provider.label)
            }
        }
        if (transcriptionProviderDraft == TranscriptionProvider.WHISPER) {
            WhisperModel.all.forEach { model ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = whisperDraft == model.id, onClick = { whisperDraft = model.id })
                    Text("${model.id} · ${model.sizeLabel}", modifier = Modifier.weight(1f))
                    if (model.id in installedModels) {
                        TextButton(onClick = { viewModel.deleteModel(model) }, enabled = !modelBusy) { Text("删除") }
                    } else {
                        TextButton(onClick = { viewModel.downloadModel(model) }, enabled = !modelBusy) { Text("下载") }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = whisperDraft == QwenModelStore.MODEL_ID, onClick = { whisperDraft = QwenModelStore.MODEL_ID })
                Text("Qwen3-ASR 0.6B INT8 · ${QwenModelStore.sizeLabel}", modifier = Modifier.weight(1f))
                if (qwenInstalled) {
                    TextButton(onClick = { viewModel.deleteQwen() }, enabled = !modelBusy) { Text("删除") }
                } else {
                    TextButton(onClick = { viewModel.downloadQwen() }, enabled = !modelBusy) { Text("下载") }
                }
            }
            if (modelStatus.isNotEmpty()) Text(modelStatus, style = MaterialTheme.typography.bodySmall)
            if (modelBusy) TextButton(onClick = { viewModel.cancelModelDownload() }) { Text("取消下载") }
        }
        if (transcriptionProviderDraft == TranscriptionProvider.ALIBABA) {
            OutlinedTextField(value = aliApiKeyDraft, onValueChange = { aliApiKeyDraft = it }, label = { Text("阿里云百炼密钥") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = aliBaseUrlDraft, onValueChange = { aliBaseUrlDraft = it }, label = { Text("阿里云接口地址") }, supportingText = { Text("北京默认 dashscope.aliyuncs.com；新加坡用 dashscope-intl.aliyuncs.com，保留 /compatible-mode/v1") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = aliModelDraft, onValueChange = { aliModelDraft = it }, label = { Text("转写模型") }, supportingText = { Text("默认 qwen3-asr-flash") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Text("Gemini（仅选择 Gemini 转写或摘要时需要）", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = apiKeyDraft,
            onValueChange = { apiKeyDraft = it },
            label = { Text(stringResource(R.string.label_gemini_api_key)) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        ExposedDropdownMenuBox(
            expanded = modelMenuExpanded,
            onExpandedChange = { modelMenuExpanded = it },
        ) {
            OutlinedTextField(
                value = modelDraft,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.label_model)) },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = modelMenuExpanded,
                onDismissRequest = { modelMenuExpanded = false },
            ) {
                viewModel.availableModels.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m) },
                        onClick = {
                            modelDraft = m
                            modelMenuExpanded = false
                        },
                    )
                }
            }
        }

        HorizontalDivider()
        com.github.meetingrecorder.ui.detail.SummaryOptionsEditor(summaryOptionsDraft, { summaryOptionsDraft = it }, showLanguage = false)
        Text("摘要语言", style = MaterialTheme.typography.titleMedium)
        listOf("Bilingual" to "中英双语摘要", "Chinese" to "中文摘要", "Original" to "原语言摘要").forEach { (value, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = summaryLanguageDraft == value, onClick = { summaryLanguageDraft = value })
                Text(label)
            }
        }
        Text(stringResource(R.string.summary_service), style = MaterialTheme.typography.titleMedium)
        ExposedDropdownMenuBox(
            expanded = providerMenuExpanded,
            onExpandedChange = { providerMenuExpanded = it },
        ) {
            OutlinedTextField(
                value = providerDraft.label,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.summary_provider)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerMenuExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = providerMenuExpanded, onDismissRequest = { providerMenuExpanded = false }) {
                SummaryProvider.entries.forEach { provider ->
                    DropdownMenuItem(
                        text = { Text(provider.label) },
                        onClick = {
                            providerDraft = provider
                            providerMenuExpanded = false
                        },
                    )
                }
            }
        }
        if (providerDraft == SummaryProvider.DEEPSEEK) {
            OutlinedTextField(
                value = deepSeekKeyDraft,
                onValueChange = { deepSeekKeyDraft = it },
                label = { Text(stringResource(R.string.deepseek_key)) },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = deepSeekModelDraft,
                onValueChange = { deepSeekModelDraft = it },
                label = { Text(stringResource(R.string.deepseek_model)) },
                supportingText = { Text(stringResource(R.string.deepseek_model_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }

        ExposedDropdownMenuBox(
            expanded = qualityMenuExpanded,
            onExpandedChange = { qualityMenuExpanded = it },
        ) {
            OutlinedTextField(
                value = audioQualityDraft.label,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.label_recording_quality)) },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = qualityMenuExpanded)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = qualityMenuExpanded,
                onDismissRequest = { qualityMenuExpanded = false },
            ) {
                viewModel.availableQualities.forEach { q ->
                    DropdownMenuItem(
                        text = { Text(q.label) },
                        onClick = {
                            audioQualityDraft = q
                            qualityMenuExpanded = false
                        },
                    )
                }
            }
        }

        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("录音实时降噪（GTCRN）")
                Text("同时保留原始录音和降噪录音", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = recordingDenoiseDraft, onCheckedChange = { recordingDenoiseDraft = it })
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.label_dnd_recording), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.desc_dnd_recording),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = dndDuringRecordingDraft,
                onCheckedChange = { enabled ->
                    dndDuringRecordingDraft = enabled
                    // Turning it on requires the one-time "Do Not Disturb access" grant.
                    if (enabled && !context.hasDndAccess()) {
                        dndAccessLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    }
                },
            )
        }

        Button(
            onClick = {
                viewModel.setWhisperModel(whisperDraft)
                viewModel.setTranscriptionProvider(transcriptionProviderDraft)
                viewModel.setAliApiKey(aliApiKeyDraft.trim())
                viewModel.setAliBaseUrl(aliBaseUrlDraft.trim())
                viewModel.setAliModel(aliModelDraft.trim())
                viewModel.setSummaryOptions(summaryOptionsDraft)
                viewModel.setSummaryLanguage(summaryLanguageDraft)
                viewModel.setSummaryProvider(providerDraft)
                viewModel.setDeepSeekApiKey(deepSeekKeyDraft)
                viewModel.setDeepSeekModel(deepSeekModelDraft)
                viewModel.setApiKey(apiKeyDraft)
                viewModel.setModel(modelDraft)
                viewModel.setAudioQuality(audioQualityDraft)
                viewModel.setRecordingDenoiseEnabled(recordingDenoiseDraft)
                viewModel.setDndDuringRecordingEnabled(dndDuringRecordingDraft)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_save))
        }
    }
}

@Composable
internal fun PromptsTab(viewModel: SettingsViewModel) {
    val transcriptionPrompt by viewModel.transcriptionPrompt.collectAsState()
    val summarizationPrompt by viewModel.summarizationPrompt.collectAsState()
    val titlePrompt by viewModel.titlePrompt.collectAsState()

    var transcriptionDraft by rememberSaveable {
        mutableStateOf(transcriptionPrompt.ifBlank { Config.DEFAULT_TRANSCRIPTION_PROMPT })
    }
    var summarizationDraft by rememberSaveable { mutableStateOf(summarizationPrompt) }
    var promptOptions by remember { mutableStateOf(viewModel.summaryOptions) }
    val templateDrafts = remember { androidx.compose.runtime.mutableStateMapOf<com.github.meetingrecorder.data.MeetingTemplate, String>() }

    var titleDraft by rememberSaveable {
        mutableStateOf(titlePrompt.ifBlank { Config.DEFAULT_TITLE_PROMPT })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("摘要提示词随会议模板切换。每个模板可单独修改；修改后点击底部保存。转写和标题提示词独立生效。")
        com.github.meetingrecorder.ui.detail.SummaryOptionsEditor(
            options = promptOptions,
            onChange = {
                promptOptions = it
                viewModel.setSummaryOptions(it)
            },
            showLanguage = false,
        )
        val selectedTemplate = promptOptions.template
        val templateDraft = templateDrafts[selectedTemplate] ?: viewModel.templatePrompt(selectedTemplate)
        PromptField(
            label = "${selectedTemplate.label} · 摘要提示词",
            value = templateDraft,
            onValueChange = { templateDrafts[selectedTemplate] = it },
            isDefault = templateDraft == selectedTemplate.defaultPrompt,
        )
        TextButton(onClick = { templateDrafts[selectedTemplate] = selectedTemplate.defaultPrompt }) {
            Text("恢复当前模板默认提示词")
        }
        PromptField(
            label = "全局补充要求（可选）",
            value = summarizationDraft,
            onValueChange = { summarizationDraft = it },
        )
        Text("对所有模板生效，默认留空。旧版自定义摘要提示词保留在这里；可清空，避免与模板冲突。", style = MaterialTheme.typography.bodySmall)
        PromptField(
            label = stringResource(R.string.label_transcription_prompt),
            value = transcriptionDraft,
            onValueChange = { transcriptionDraft = it },
            isDefault = transcriptionDraft == Config.DEFAULT_TRANSCRIPTION_PROMPT,
        )
        PromptField(
            label = stringResource(R.string.label_meeting_title_prompt),
            value = titleDraft,
            onValueChange = { titleDraft = it },
            isDefault = titleDraft == Config.DEFAULT_TITLE_PROMPT,
        )
        Button(
            onClick = {
                // Store blank when the user hasn't changed from the default,
                // so the app continues to pick up future default changes.
                viewModel.setTranscriptionPrompt(
                    if (transcriptionDraft == Config.DEFAULT_TRANSCRIPTION_PROMPT) "" else transcriptionDraft,
                )
                templateDrafts.forEach { (template, prompt) -> viewModel.setTemplatePrompt(template, prompt) }
                viewModel.setSummarizationPrompt(summarizationDraft)
                viewModel.setTitlePrompt(
                    if (titleDraft == Config.DEFAULT_TITLE_PROMPT) "" else titleDraft,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_save))
        }
    }
}

@Composable
private fun PromptField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isDefault: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = if (isDefault) {
            { Text(stringResource(R.string.hint_showing_default)) }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth(),
        minLines = 5,
    )
}

private fun Context.hasDndAccess(): Boolean =
    getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
