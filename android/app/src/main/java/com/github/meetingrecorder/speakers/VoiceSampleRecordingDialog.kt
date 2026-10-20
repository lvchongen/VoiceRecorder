package com.github.meetingrecorder.speakers

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.github.meetingrecorder.audio.AudioRecorder
import com.github.meetingrecorder.audio.RecordingPhase
import com.github.meetingrecorder.audio.RecordingService
import kotlinx.coroutines.delay
import java.io.File
import java.nio.file.Files

/** Short foreground-only enrollment recording, separate from the meeting recording service. */
@Composable
internal fun VoiceSampleRecordingDialog(name: String?, onDismiss: () -> Unit, onRecorded: (File, String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val recorder = remember { AudioRecorder(context.applicationContext) }
    var directory by remember { mutableStateOf<File?>(null) }
    var recording by remember { mutableStateOf(false) }
    var seconds by remember { mutableStateOf(0) }
    var startedAt by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var disposed by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }

    fun discard() {
        recorder.stop()
        recording = false
        directory?.deleteRecursively()
        directory = null
    }

    fun start() {
        if (disposed || recording) return
        if (RecordingService.status.value.phase == RecordingPhase.RECORDING) {
            error = "请先结束会议录音，再录制声纹"
            return
        }
        error = null
        try {
            directory = Files.createTempDirectory(context.cacheDir.toPath(), "voice-record-").toFile()
            recorder.start(requireNotNull(directory), bitrate = 96_000)
            startedAt = SystemClock.elapsedRealtime()
            seconds = 0
            recording = true
        } catch (e: Exception) {
            discard()
            error = e.message ?: "无法开始录音，请检查麦克风权限"
        }
    }

    fun finish() {
        recorder.stop()
        recording = false
        val audio = directory?.let { File(it, "recording.m4a") }
        if (recorder.wasInterrupted || audio == null || audio.length() == 0L) {
            discard()
            error = "录音中断或没有收到声音，请重新录制"
        } else if (name == null) {
            naming = true
        } else {
            directory = null // The ViewModel now owns the temporary file and its cleanup.
            onRecorded(audio, name)
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!disposed) {
            if (granted) start() else error = "需要麦克风权限才能录制声纹"
        }
    }
    DisposableEffect(recorder, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                discard()
                onDismiss()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            disposed = true
            lifecycleOwner.lifecycle.removeObserver(observer)
            discard()
        }
    }
    LaunchedEffect(recording) {
        while (recording) {
            seconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
            if (seconds >= 30) {
                finish()
                break
            }
            delay(200)
        }
    }
    AlertDialog(
        onDismissRequest = {
            discard()
            onDismiss()
        },
        title = { Text(if (naming) "填写姓名" else "录制声纹") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (naming) {
                    Text("录音已完成，请为这份声纹填写姓名。")
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        singleLine = true,
                        label = { Text("姓名") },
                        isError = nameDraft.trim().length > 80,
                    )
                } else {
                    Text(if (name == null) "第一步：录制本人的声音" else "为「$name」补充声音样本")
                    Text("请本人在安静环境中连续说话 10–20 秒，避免其他人同时讲话。最多录制 30 秒。")
                    if (recording) Text("正在录制：$seconds 秒", style = MaterialTheme.typography.titleMedium)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = if (naming) nameDraft.trim().length in 1..80 else !recording || seconds >= 5, onClick = {
                if (naming) {
                    val audio = File(requireNotNull(directory), "recording.m4a")
                    directory = null
                    onRecorded(audio, nameDraft.trim())
                } else if (recording) {
                    finish()
                } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    start()
                } else {
                    permission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }) {
                Text(
                    when {
                        naming -> "保存声纹"
                        recording && name == null -> "完成录制"
                        recording -> "完成并更新"
                        else -> "开始录制"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = {
                discard()
                onDismiss()
            }) { Text("取消") }
        },
    )
}
