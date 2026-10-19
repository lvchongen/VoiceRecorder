package com.github.meetingrecorder.ui.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.ContextThemeWrapper
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.meetingrecorder.R
import com.github.meetingrecorder.appViewModelFactory
import com.github.meetingrecorder.speakers.SpeakerMeetingPanel
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingDetailScreen(
    meetingPath: String,
    onBack: () -> Unit,
    viewModel: MeetingDetailViewModel = viewModel(factory = appViewModelFactory),
    initialSpeakerTab: Boolean = false,
) {
    LaunchedEffect(meetingPath) { viewModel.load(meetingPath) }

    val transcript by viewModel.transcript.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val hasAudio by viewModel.hasAudio.collectAsState()
    val genState by viewModel.genState.collectAsState()
    val currentNotes = notes
    val currentTranscript = transcript
    val processing = genState is GenState.Processing
    var selectedTab by rememberSaveable(meetingPath) { mutableIntStateOf(if (initialSpeakerTab) 2 else 0) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showOptions by remember(meetingPath) { mutableStateOf(false) }
    var showHistory by remember(meetingPath) { mutableStateOf(false) }
    var draft by remember(meetingPath) { mutableStateOf(viewModel.defaultSummaryOptions) }
    val preview by viewModel.preview.collectAsState()
    val versions by viewModel.versions.collectAsState()

    if (showOptions) {
        AlertDialog(
            onDismissRequest = { showOptions = false },
            title = { Text("生成会议纪要") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SummaryOptionsEditor(draft, { draft = it })
                    Text(if (currentTranscript.isNullOrBlank()) "先转写录音，再生成纪要预览。采用后才保存纪要。" else "复用已有逐字稿。生成后先预览，采用后才替换当前纪要。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showOptions = false
                    if (currentTranscript.isNullOrBlank()) viewModel.generateTranscriptAndNotes(draft) else viewModel.regenerateNotes(draft)
                }) { Text("生成预览") }
            },
            dismissButton = { TextButton(onClick = { showOptions = false }) { Text("取消") } },
        )
    }
    if (showHistory) {
        AlertDialog(
            onDismissRequest = { showHistory = false },
            title = { Text("纪要历史版本") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (versions.isEmpty()) Text("采用新纪要后，会在这里保留版本。")
                    versions.forEach { version ->
                        TextButton(onClick = {
                            showHistory = false
                            viewModel.previewVersion(version)
                        }) {
                            Text(DateFormat.getDateTimeInstance().format(Date(version.createdAt)) + " · " + (version.options?.template?.label ?: "原有纪要") + " · " + (version.options?.detail?.label ?: "") + "\n" + (version.model ?: "模型未记录"))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHistory = false }) { Text("关闭") } },
        )
    }
    preview?.let { version ->
        var showEvidence by remember(version.id) { mutableStateOf(false) }
        Dialog(onDismissRequest = { if (!processing) viewModel.dismissPreview() }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Text("纪要预览", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                    Text(
                        listOfNotNull(version.options?.template?.label, version.options?.detail?.label, version.model).joinToString(" · "),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    version.sourceTranscript?.let {
                        TextButton(onClick = { showEvidence = !showEvidence }) { Text(if (showEvidence) "返回纪要" else "查看引用原文") }
                    }
                    ContentText(if (showEvidence) com.github.meetingrecorder.data.numberedTranscript(version.sourceTranscript.orEmpty()) else version.notes, Modifier.weight(1f))
                    if (genState is GenState.Error) ErrorBanner((genState as GenState.Error).msg)
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = viewModel::dismissPreview, enabled = !processing) { Text("保留原版") }
                        Button(onClick = viewModel::adoptPreview, enabled = !processing) { Text(if (processing) "保存中…" else "采用此版本") }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_meeting_detail)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    // Regenerate is offered for notes only, and only once notes exist.
                    if (selectedTab == 0 && currentNotes != null && !processing) {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more_options))
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("历史版本") },
                                onClick = {
                                    menuExpanded = false
                                    showHistory = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_regenerate_notes)) },
                                onClick = {
                                    menuExpanded = false
                                    draft = viewModel.defaultSummaryOptions
                                    showOptions = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.label_notes)) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.label_transcript)) },
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = {
                        viewModel.stop()
                        selectedTab = 2
                    },
                    text = { Text("说话人") },
                )
                if (hasAudio) {
                    Tab(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        text = { Text(stringResource(R.string.label_audio)) },
                    )
                }
            }

            when (val state = genState) {
                is GenState.Processing -> ProcessingBody(state.status)
                is GenState.Error -> ErrorBanner(state.msg)
                GenState.Idle -> {}
            }
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                when (selectedTab) {
                    0 -> NotesTabBody(
                        notes = currentNotes,
                        transcript = currentTranscript,
                        hasAudio = hasAudio,
                        genState = genState,
                        onGenerateNotes = {
                            draft = viewModel.defaultSummaryOptions
                            showOptions = true
                        },
                        onGenerateAll = {
                            draft = viewModel.defaultSummaryOptions
                            showOptions = true
                        },
                    )
                    1 -> TranscriptTabBody(
                        transcript = currentTranscript,
                        hasAudio = hasAudio,
                        genState = genState,
                        onGenerateAll = {
                            draft = viewModel.defaultSummaryOptions
                            showOptions = true
                        },
                    )
                    2 -> SpeakerMeetingPanel(
                        meetingPath = meetingPath,
                        transcriptionBusy = processing,
                        onTranscribe = {
                            selectedTab = 0
                            viewModel.generateTranscriptAndNotes(withSpeakers = true)
                        },
                    )
                    3 -> AudioPlayer(viewModel)
                }
            }
        }
    }
}

@Composable
private fun NotesTabBody(
    notes: String?,
    transcript: String?,
    hasAudio: Boolean,
    genState: GenState,
    onGenerateNotes: () -> Unit,
    onGenerateAll: () -> Unit,
) {
    when {
        notes != null -> Column(Modifier.fillMaxSize()) {
            // Surface regenerate failures without losing the existing notes.
            ContentText(notes, Modifier.weight(1f))
        }
        // Reached only when notes are absent, so hasNotes is false here.
        else -> EmptyState(placeholderRes = R.string.no_notes) {
            if (genState !is GenState.Processing) {
                when (notesTabAction(hasNotes = false, hasTranscript = transcript != null, hasAudio = hasAudio)) {
                    // Transcript already present → just summarize it, no audio re-upload.
                    GenerateAction.NOTES_ONLY -> Button(onClick = onGenerateNotes) {
                        Text(stringResource(R.string.action_generate_notes))
                    }
                    GenerateAction.TRANSCRIBE_AND_NOTES -> Button(onClick = onGenerateAll) {
                        Text(stringResource(R.string.action_generate_transcript_notes))
                    }
                    GenerateAction.NONE -> {}
                }
            }
        }
    }
}

@Composable
private fun TranscriptTabBody(
    transcript: String?,
    hasAudio: Boolean,
    genState: GenState,
    onGenerateAll: () -> Unit,
) {
    when {
        // Keep the transcript readable even while notes are being generated in the background.
        transcript != null -> ContentText(com.github.meetingrecorder.data.numberedTranscript(transcript))
        // Reached only when the transcript is absent, so hasTranscript is false here.
        else -> EmptyState(placeholderRes = R.string.no_transcript) {
            if (genState !is GenState.Processing && transcriptTabAction(hasTranscript = false, hasAudio = hasAudio) ==
                GenerateAction.TRANSCRIBE_AND_NOTES
            ) {
                Button(onClick = onGenerateAll) {
                    Text(stringResource(R.string.action_generate_transcript_notes))
                }
            }
        }
    }
}

@Composable
private fun ContentText(text: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val renderer = remember(context, dark) {
        MarkdownRenderer(ContextThemeWrapper(context, if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar))
    }
    var showSource by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    Column(modifier.fillMaxSize().clipToBounds()) {
        Row {
            TextButton(onClick = { showSource = !showSource }) { Text(if (showSource) "阅读视图" else "Markdown 原文") }
            TextButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Meeting notes", text))
            }) { Text("复制 Markdown") }
        }
        AndroidView(
            // AndroidView does not clip native drawing to its Compose bounds by default.
            // Keep scrolled text below the toolbar and tabs, including overscroll.
            modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds(),
            factory = { ctx ->
                ScrollView(ctx).apply {
                    clipChildren = true
                    clipToPadding = true
                    addView(
                        TextView(ctx).apply {
                            textSize = 16f
                            setTextIsSelectable(true)
                            setLineSpacing(0f, 1.2f)
                            val padding = (16 * resources.displayMetrics.density).toInt()
                            setPadding(padding, padding, padding, padding)
                        },
                        android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT),
                    )
                }
            },
            update = { scroll ->
                val view = scroll.getChildAt(0) as TextView
                view.setTextColor(color)
                view.setLinkTextColor(linkColor)
                val key = Triple(text, showSource, color)
                if (view.tag != key) {
                    if (showSource) view.text = text else renderer.bind(view, text)
                    view.tag = key
                }
            },
        )
    }
}

@Composable
private fun ErrorBanner(msg: String) {
    Text(
        text = msg,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun ProcessingBody(status: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(Modifier.size(24.dp))
        Text(status, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EmptyState(
    placeholderRes: Int,
    action: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(placeholderRes),
            modifier = Modifier.padding(bottom = 16.dp),
        )
        action()
    }
}
