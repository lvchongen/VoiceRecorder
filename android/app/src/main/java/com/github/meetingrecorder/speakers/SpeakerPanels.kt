package com.github.meetingrecorder.speakers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.meetingrecorder.appViewModelFactory
import java.util.Locale

private fun timeLabel(ms: Long): String = "%02d:%02d:%02d".format(Locale.ROOT, ms / 3600000, ms / 60000 % 60, ms / 1000 % 60)

@Composable
fun SpeakerMeetingPanel(
    meetingPath: String,
    onTranscribe: () -> Unit,
    transcriptionBusy: Boolean = false,
    viewModel: SpeakerViewModel = viewModel(factory = appViewModelFactory),
) {
    LaunchedEffect(meetingPath) { viewModel.load(meetingPath) }
    DisposableEffect(viewModel) { onDispose { viewModel.stopSample() } }
    val state by viewModel.state.collectAsState()
    var editing by remember(meetingPath) { mutableStateOf<MeetingSpeaker?>(null) }
    var transcribeConfirmation by remember { mutableStateOf(false) }
    var clearConfirmation by remember { mutableStateOf(false) }
    val disabled = state.busy || transcriptionBusy
    if (clearConfirmation) {
        AlertDialog(
            onDismissRequest = { clearConfirmation = false },
            title = { Text("清除本场分析") },
            text = { Text("清除本场编号关联、姓名、声纹特征和试听片段。原始录音与逐字稿不变，已主动存入声纹库的独立样本也会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    clearConfirmation = false
                    viewModel.clearAnalysis()
                }) { Text("清除") }
            },
            dismissButton = { TextButton(onClick = { clearConfirmation = false }) { Text("取消") } },
        )
    }
    if (transcribeConfirmation) {
        AlertDialog(
            onDismissRequest = { transcribeConfirmation = false },
            title = { Text("分人转写 A/B/C") },
            text = { Text("按说话人片段重新转写并生成纪要预览。已有逐字稿将被更新；现有纪要保持不变，直到你采用新版本。云端转写会按片段发起请求。") },
            confirmButton = {
                TextButton(onClick = {
                    transcribeConfirmation = false
                    viewModel.stopSample()
                    onTranscribe()
                }) { Text("开始转写") }
            },
            dismissButton = { TextButton(onClick = { transcribeConfirmation = false }) { Text("取消") } },
        )
    }
    editing?.let { speaker ->
        SpeakerNameDialog(speaker, state, viewModel, onDismiss = { editing = null })
    }
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
                Text("本场会议 A/B/C", style = MaterialTheme.typography.titleMedium)
                Text("编号按首次发言顺序分配，仅在本场会议有效。命名和声纹均为可选操作；匹配结果由你确认。", style = MaterialTheme.typography.bodySmall)
                if (!state.modelsReady) {
                    Text("请在设置 → 声纹下载本地分人模型（${SpeakerModelStore.sizeLabel}）。普通录音保存不受影响。")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::analyze, enabled = state.modelsReady && !disabled) { Text("分析说话人") }
                    OutlinedButton(onClick = { transcribeConfirmation = true }, enabled = state.modelsReady && !disabled) { Text("分人转写") }
                }
                if (state.meeting != null) TextButton(onClick = { clearConfirmation = true }, enabled = !disabled) { Text("清除本场分析") }
                SpeakerStatus(state, viewModel::cancel)
            }
        }
        val meeting = state.meeting
        if (meeting != null) {
            items(meeting.speakers, key = { it.label }) { speaker ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(speaker.label + (speaker.name?.let { " · $it" } ?: ""), style = MaterialTheme.typography.titleMedium)
                        if (speaker.profileId != null && state.profiles.none { it.id == speaker.profileId }) Text("声纹档案已删除；保留本场会议的姓名", style = MaterialTheme.typography.bodySmall)
                        if (speaker.samples.isEmpty()) Text("暂无可保存的清晰样本，可仅为本场会议命名。", style = MaterialTheme.typography.bodySmall)
                        speaker.samples.forEach { sample ->
                            TextButton(onClick = { viewModel.playMeetingSample(speaker.label, sample.id) }, enabled = !disabled) {
                                Text((if (state.playingSample == sample.id) "停止试听" else "试听样本") + " · " + timeLabel(sample.startMs))
                            }
                        }
                        TextButton(onClick = { editing = speaker }, enabled = !disabled) { Text("识别并命名（可选）") }
                        if (speaker.name != null) TextButton(onClick = { viewModel.clearName(speaker.label) }, enabled = !disabled) { Text("清除本场命名与关联") }
                    }
                }
            }
            item { Text("发言时间线 · 分人结果请核对", style = MaterialTheme.typography.titleSmall) }
            items(meeting.turns) { turn ->
                val speaker = meeting.speakers.firstOrNull { it.label == turn.label }
                Text("${timeLabel(turn.startMs)}–${timeLabel(turn.endMs)}  ${turn.label ?: "重叠/未确认"}${speaker?.name?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Text("姓名关联保存在本场会议；不会自动改写已生成的逐字稿或纪要。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 16.dp)) }
    }
}

@Composable
private fun SpeakerNameDialog(speaker: MeetingSpeaker, state: SpeakerUiState, viewModel: SpeakerViewModel, onDismiss: () -> Unit) {
    var name by remember(speaker.label) { mutableStateOf(speaker.name.orEmpty()) }
    var selected by remember(speaker.label) { mutableStateOf(speaker.profileId?.takeIf { id -> state.profiles.any { it.id == id } }) }
    var saveVoice by remember { mutableStateOf(false) }
    var sampleIds by remember { mutableStateOf(speaker.samples.take(1).map { it.id }.toSet()) }
    val suggestions = remember(speaker.samples, state.profiles) { voiceCandidates(speaker.samples, state.profiles) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("为 ${speaker.label} 确认姓名") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("相似度仅供参考，不会自动认定身份。可以选已有档案，也可以仅输入本场姓名。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = name, onValueChange = {
                    name = it
                    selected = null
                }, label = { Text("姓名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == null, onClick = { selected = null })
                    Text("手动输入姓名")
                }
                state.profiles.sortedByDescending { profile -> suggestions.firstOrNull { it.profile.id == profile.id }?.similarity ?: -2f }.forEach { profile ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected == profile.id, onClick = {
                            selected = profile.id
                            name = profile.name
                        })
                        Text(profile.name + (suggestions.firstOrNull { it.profile.id == profile.id }?.let { " · 相似度 %.2f".format(Locale.ROOT, it.similarity) } ?: ""))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = saveVoice, enabled = speaker.samples.isNotEmpty(), onCheckedChange = { saveVoice = it })
                    Text(if (selected == null) "保存新声纹供以后识别" else "追加本次样本到此声纹")
                }
                if (saveVoice) {
                    Text("请试听并选择要保存的样本。可追加不同状态下的声音，不会覆盖已有样本。", style = MaterialTheme.typography.bodySmall)
                    speaker.samples.forEach { sample ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = sample.id in sampleIds, onCheckedChange = { checked -> sampleIds = if (checked) sampleIds + sample.id else sampleIds - sample.id })
                            TextButton(onClick = { viewModel.playMeetingSample(speaker.label, sample.id) }) {
                                Text((if (state.playingSample == sample.id) "停止" else "试听") + " · " + timeLabel(sample.startMs))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.trim().isNotEmpty() && name.trim().length <= 80 && (!saveVoice || sampleIds.isNotEmpty()), onClick = {
                viewModel.stopSample()
                viewModel.confirm(speaker.label, name, selected, saveVoice, sampleIds)
                onDismiss()
            }) { Text("确认保存") }
        },
        dismissButton = {
            TextButton(onClick = {
                viewModel.stopSample()
                onDismiss()
            }) { Text("保持原样") }
        },
    )
}

@Composable
fun VoiceprintPanel(viewModel: SpeakerViewModel = viewModel(factory = appViewModelFactory)) {
    LaunchedEffect(viewModel) { viewModel.load() }
    DisposableEffect(viewModel) { onDispose { viewModel.stopSample() } }
    val state by viewModel.state.collectAsState()
    var enabled by remember { mutableStateOf(viewModel.separationEnabled) }
    var renaming by remember { mutableStateOf<VoiceProfile?>(null) }
    var deleteAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    renaming?.let { profile ->
        var draft by remember(profile.id) { mutableStateOf(profile.name) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("修改声纹姓名") }, text = {
            OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true, label = { Text("姓名") })
        }, confirmButton = {
            TextButton(enabled = draft.trim().length in 1..80, onClick = {
                viewModel.renameProfile(profile.id, draft)
                renaming = null
            }) { Text("保存") }
        }, dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } })
    }
    deleteAction?.let { action ->
        AlertDialog(onDismissRequest = { deleteAction = null }, title = { Text("确认删除") }, text = { Text("删除所选声纹或样本后，不再用于以后匹配。会议录音、逐字稿和已确认的本场姓名会保留。") }, confirmButton = {
            TextButton(onClick = {
                deleteAction = null
                action()
            }) { Text("删除") }
        }, dismissButton = { TextButton(onClick = { deleteAction = null }) { Text("取消") } })
    }
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("本地分人与声纹", style = MaterialTheme.typography.titleMedium)
                Text("分人分析会保留少量本场试听片段，可在会议页清除。只有你确认保存后，才加入跨会议声纹库；均仅保存在本机。", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = enabled, enabled = !state.busy, onCheckedChange = {
                        enabled = it
                        viewModel.setSeparationEnabled(it)
                    })
                    Text("转写时自动分人 A/B/C")
                }
                Text("模型下载后生效；未下载时继续使用原有转写。普通录音保存不会触发分析。分人转写按发言片段处理，云端服务可能增加请求次数。", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::downloadModels, enabled = !state.busy && !state.modelsReady) { Text(if (state.modelsReady) "模型已安装" else "下载模型 · ${SpeakerModelStore.sizeLabel}") }
                    if (state.modelsReady) TextButton(onClick = viewModel::deleteModels, enabled = !state.busy) { Text("删除模型") }
                }
                SpeakerStatus(state, viewModel::cancel)
                Text("声纹档案", style = MaterialTheme.typography.titleMedium)
                if (state.profiles.isEmpty()) Text("暂无声纹。请在会议 → 说话人中试听、命名并选择保存声纹。")
            }
        }
        items(state.profiles, key = { it.id }) { profile ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                    Text("${profile.samples.size} 份样本", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { renaming = profile }, enabled = !state.busy) { Text("命名") }
                        TextButton(onClick = { deleteAction = { viewModel.deleteProfile(profile.id) } }, enabled = !state.busy) { Text("删除声纹") }
                    }
                    profile.samples.forEach { sample ->
                        Text(sample.source + " · " + timeLabel(sample.startMs), style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { viewModel.playProfileSample(profile.id, sample.id) }, enabled = !state.busy) { Text(if (state.playingSample == sample.id) "停止试听" else "试听") }
                            TextButton(onClick = { deleteAction = { viewModel.removeSample(profile.id, sample.id) } }, enabled = !state.busy) { Text("移除样本") }
                        }
                    }
                }
            }
        }
        item { Text("追加样本：在另一场会议的说话人页面选择此档案，再勾选“追加本次样本”。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 16.dp)) }
    }
}

@Composable
private fun SpeakerStatus(state: SpeakerUiState, onCancel: () -> Unit) {
    if (state.busy) {
        CircularProgressIndicator()
        Text(state.message.ifBlank { "正在加载…" })
        TextButton(onClick = onCancel) { Text("取消") }
    } else if (state.message.isNotEmpty()) Text(state.message)
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
