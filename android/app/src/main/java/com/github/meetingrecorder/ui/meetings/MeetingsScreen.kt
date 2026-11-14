package com.github.meetingrecorder.ui.meetings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.meetingrecorder.R
import com.github.meetingrecorder.appViewModelFactory
import com.github.meetingrecorder.data.Meeting
import com.github.meetingrecorder.util.formatMeetingSubtitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingsScreen(
    onBack: () -> Unit,
    onMeetingClick: (String) -> Unit,
    onRecord: (() -> Unit)? = null,
    onImport: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    viewModel: MeetingsViewModel = viewModel(factory = appViewModelFactory),
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(MeetingFilter.ALL) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.loadMeetings() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val meetings by viewModel.meetings.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    var renamingMeeting by remember { mutableStateOf<Meeting?>(null) }
    var deletingMeeting by remember { mutableStateOf<Meeting?>(null) }

    renamingMeeting?.let { meeting ->
        RenameDialog(
            currentTitle = meeting.title ?: "",
            onConfirm = { newTitle ->
                viewModel.renameMeeting(meeting, newTitle)
                renamingMeeting = null
            },
            onDismiss = { renamingMeeting = null },
        )
    }

    deletingMeeting?.let { meeting ->
        DeleteDialog(
            title = meeting.title ?: meeting.timeLabel,
            onConfirm = {
                viewModel.deleteMeeting(meeting)
                deletingMeeting = null
            },
            onDismiss = { deletingMeeting = null },
        )
    }

    val visibleMeetings = remember(meetings, query, filter) { filterMeetings(meetings, query, filter) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (onRecord != null) "VoiceRecorder" else "会议", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    if (onRecord == null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    onSettings?.let { action -> IconButton(onClick = action) { Icon(Icons.Default.Settings, "设置") } }
                },
            )
        },
        bottomBar = {
            if (onRecord != null && onImport != null) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onRecord, modifier = Modifier.weight(1f).heightIn(min = 52.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer), shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Default.Mic, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("开始录音")
                        }
                        OutlinedButton(onClick = onImport, modifier = Modifier.heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Default.FolderOpen, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("导入")
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("会议", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
                OutlinedTextField(value = query, onValueChange = { query = it }, placeholder = { Text("搜索会议") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MeetingFilter.entries.forEach { option ->
                        FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option.label) })
                    }
                }
            }
            if (isLoading && meetings.isEmpty()) {
                item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            } else if (visibleMeetings.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.GraphicEq, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (meetings.isEmpty()) "还没有会议录音" else "没有匹配的会议", style = MaterialTheme.typography.titleMedium)
                        Text(if (meetings.isEmpty()) "开始录音，或导入已有音频" else "试试其他关键词或筛选条件", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                visibleMeetings.groupBy { it.date.toLocalDate() }.forEach { (date, group) ->
                    item(key = "date-$date") {
                        val today = java.time.LocalDate.now()
                        Text(
                            when (date) {
                                today -> "今天"
                                today.minusDays(1) -> "昨天"
                                else -> date.toString()
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    items(group, key = { it.path.absolutePath }) { meeting ->
                        MeetingCard(meeting, { onMeetingClick(meeting.path.absolutePath) }, { renamingMeeting = meeting }, { deletingMeeting = meeting })
                    }
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(
    currentTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf(currentTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_rename_title)) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(stringResource(R.string.label_title)) },
                placeholder = { Text(stringResource(R.string.placeholder_meeting_title)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }) { Text(stringResource(R.string.action_rename)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun DeleteDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除会议？") },
        text = { Text("将永久删除“$title”及其所有文件。") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun MeetingCard(meeting: Meeting, onClick: () -> Unit, onRenameClick: () -> Unit, onDeleteClick: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Icon(Icons.Default.GraphicEq, null, Modifier.padding(10.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(meeting.title ?: meeting.timeLabel, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(formatMeetingSubtitle(meeting.date, meeting.durationSeconds), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    when {
                        meeting.hasNotes -> "已总结"
                        meeting.hasTranscript -> "待总结"
                        else -> "待转写"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "会议操作", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("重命名") }, onClick = {
                        menuOpen = false
                        onRenameClick()
                    })
                    DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) }, onClick = {
                        menuOpen = false
                        onDeleteClick()
                    })
                }
            }
        }
    }
}

internal enum class MeetingFilter(val label: String) { ALL("全部"), UNTRANSCRIBED("待转写"), SUMMARIZED("已总结") }

internal fun filterMeetings(meetings: List<Meeting>, query: String, filter: MeetingFilter): List<Meeting> = meetings.filter {
    val matches = when (filter) {
        MeetingFilter.ALL -> true
        MeetingFilter.UNTRANSCRIBED -> !it.hasTranscript
        MeetingFilter.SUMMARIZED -> it.hasNotes
    }
    matches && (it.title.orEmpty() + " " + it.timeLabel + " " + it.date.toLocalDate()).contains(query.trim(), ignoreCase = true)
}
