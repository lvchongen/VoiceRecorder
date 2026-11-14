package com.github.meetingrecorder.ui.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun AudioPlayer(viewModel: MeetingDetailViewModel, onTranscript: () -> Unit = {}, onNotes: () -> Unit = {}) {
    val hasOriginal by viewModel.hasOriginal.collectAsState()
    val usingOriginal by viewModel.usingOriginal.collectAsState()
    val warning by viewModel.audioWarning.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val position by viewModel.positionMs.collectAsState()
    val duration by viewModel.durationMs.collectAsState()
    val waveform by viewModel.waveform.collectAsState()
    val transcript by viewModel.transcript.collectAsState()
    val notes by viewModel.notes.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        AudioPlaybackControls(
            positionMs = position,
            durationMs = duration,
            isPlaying = isPlaying,
            sourceKey = usingOriginal,
            waveform = waveform,
            sourceSelector = {
                if (hasOriginal) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !usingOriginal, onClick = { viewModel.selectOriginal(false) }, label = { Text("降噪录音") }, modifier = Modifier.weight(1f))
                        FilterChip(selected = usingOriginal, onClick = { viewModel.selectOriginal(true) }, label = { Text("原始录音") }, modifier = Modifier.weight(1f))
                    }
                }
            },
            onSeek = viewModel::seekTo,
            onSkip = viewModel::skipBy,
            onPlayPause = viewModel::playPause,
            onStop = viewModel::stop,
        )
        OutlinedCard(
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        ) {
            PlaybackResultRow("转写结果", transcript != null, onTranscript)
            androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            PlaybackResultRow("总结结果", notes != null, onNotes)
        }
    }
}

@Composable
internal fun AudioPlaybackControls(
    positionMs: Int,
    durationMs: Int,
    isPlaying: Boolean,
    sourceKey: Boolean = false,
    waveform: List<Float> = emptyList(),
    sourceSelector: @Composable () -> Unit = {},
    onSeek: (Int) -> Unit,
    onSkip: (Int) -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
) {
    // Keep the user's preview independent of playback ticks until the drag is released.
    var dragPosition by remember(sourceKey, durationMs) { mutableStateOf<Float?>(null) }
    val enabled = durationMs > 0
    val shownPosition = (dragPosition?.toInt() ?: positionMs).coerceIn(0, durationMs.coerceAtLeast(0))
    OutlinedCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            sourceSelector()
            Text(if (!enabled) "正在准备音频" else if (isPlaying) "正在播放" else "已暂停", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val playedColor = MaterialTheme.colorScheme.primary
            val remainingColor = MaterialTheme.colorScheme.outline
            Canvas(Modifier.fillMaxWidth().height(88.dp)) {
                if (waveform.isEmpty()) {
                    drawLine(remainingColor.copy(alpha = 0.5f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.dp.toPx())
                } else {
                    val step = size.width / waveform.size
                    val fraction = if (durationMs > 0) shownPosition.toFloat() / durationMs else 0f
                    waveform.forEachIndexed { index, value ->
                        val x = step * (index + 0.5f)
                        val bar = (value.coerceIn(0f, 1f) * size.height * 0.85f).coerceAtLeast(2.dp.toPx())
                        drawLine(if (index.toFloat() / waveform.size < fraction) playedColor else remainingColor.copy(alpha = 0.6f), Offset(x, (size.height - bar) / 2), Offset(x, (size.height + bar) / 2), strokeWidth = step * 0.45f, cap = StrokeCap.Round)
                    }
                    drawLine(playedColor, Offset(size.width * fraction, 0f), Offset(size.width * fraction, size.height), strokeWidth = 1.5.dp.toPx())
                }
            }
            Column {
                Slider(
                    value = shownPosition.toFloat(),
                    onValueChange = { dragPosition = it },
                    onValueChangeFinished = {
                        dragPosition?.let { onSeek(it.toInt()) }
                        dragPosition = null
                    },
                    valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "播放进度" },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(playbackTime(shownPosition), style = MaterialTheme.typography.bodyMedium)
                    Text(playbackTime(durationMs), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconButton(onClick = { onSkip(-10_000) }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Replay10, "后退10秒", Modifier.size(32.dp))
                }
                FilledIconButton(onClick = onPlayPause, enabled = enabled, modifier = Modifier.size(72.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.primary)) {
                    Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (isPlaying) "暂停" else "播放", Modifier.size(40.dp))
                }
                IconButton(onClick = { onSkip(10_000) }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Forward10, "快进10秒", Modifier.size(32.dp))
                }
            }
            TextButton(onClick = onStop, enabled = enabled) { Text("停止并回到开头") }
        }
    }
}

internal fun playbackTime(milliseconds: Int): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) {
        "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    } else {
        "%02d:%02d".format(seconds / 60, seconds % 60)
    }
}

@Composable
private fun PlaybackResultRow(title: String, completed: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Default.Description, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Text(if (completed) "已完成" else "尚未生成", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp))
    }
}
