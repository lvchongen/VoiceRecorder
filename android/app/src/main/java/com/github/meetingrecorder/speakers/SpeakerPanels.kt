package com.github.meetingrecorder.speakers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
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

@Composable
fun VoiceprintPanel(viewModel: SpeakerViewModel = viewModel(factory = appViewModelFactory)) {
    LaunchedEffect(viewModel) { viewModel.load() }
    DisposableEffect(viewModel) { onDispose { viewModel.stopSample() } }
    val state by viewModel.state.collectAsState()
    var renaming by remember { mutableStateOf<VoiceProfile?>(null) }
    var recordingFor by remember { mutableStateOf<VoiceProfile?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleteAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    recordingFor?.let { profile ->
        VoiceSampleRecordingDialog(
            name = profile.name,
            onDismiss = { recordingFor = null },
            onRecorded = { audio, _ ->
                recordingFor = null
                viewModel.updateRecordedVoiceSample(audio, profile.id)
            },
        )
    }
    if (adding) {
        VoiceSampleRecordingDialog(
            name = null,
            onDismiss = { adding = false },
            onRecorded = { audio, name ->
                adding = false
                viewModel.addRecordedVoiceSample(audio, name)
            },
        )
    }
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
                Text("自动识别并保存声纹，你只需管理名字和样本。", style = MaterialTheme.typography.bodySmall)
                if (!state.modelsReady) Text("正在准备声纹识别，录音和转写可正常使用。", style = MaterialTheme.typography.bodySmall)
                SpeakerStatus(state, viewModel::cancel)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("声纹档案", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = {
                        viewModel.stopSample()
                        adding = true
                    }, enabled = !state.busy) { Text("添加") }
                }
                if (state.profiles.isEmpty()) Text("暂无声纹。录音保存后会自动添加。")
            }
        }
        items(state.profiles, key = { it.id }) { profile ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                    var expanded by remember(profile.id) { mutableStateOf(false) }
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起样本" else "${profile.samples.size} 份样本") }
                    Row {
                        profile.samples.firstOrNull()?.let { sample ->
                            TextButton(onClick = { viewModel.playProfileSample(profile.id, sample.id) }, enabled = !state.busy) { Text(if (state.playingSample == sample.id) "停止" else "试听") }
                        }
                        TextButton(onClick = { renaming = profile }, enabled = !state.busy) { Text("命名") }
                        TextButton(onClick = {
                            viewModel.stopSample()
                            recordingFor = profile
                        }, enabled = !state.busy) { Text("更新") }
                        TextButton(onClick = { deleteAction = { viewModel.deleteProfile(profile.id) } }, enabled = !state.busy) { Text("删除声纹") }
                    }
                    if (expanded) {
                        profile.samples.forEach { sample ->
                            Text("样本 ${profile.samples.indexOf(sample) + 1}", style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = { viewModel.playProfileSample(profile.id, sample.id) }, enabled = !state.busy) { Text(if (state.playingSample == sample.id) "停止试听" else "试听样本") }
                                TextButton(onClick = { deleteAction = { viewModel.removeSample(profile.id, sample.id) } }, enabled = !state.busy) { Text("移除样本") }
                            }
                        }
                    }
                }
            }
        }
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
