package com.github.meetingrecorder.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import com.github.meetingrecorder.data.DenoiseModel
import java.io.File

/** Captures once; raw AAC is saved before sending each frame to the optional denoising branch. */
class RealtimeRecorder(private val context: Context) {
    private var capture: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile private var running = false

    @Volatile var interrupted = false
        private set
    private var interruption: (() -> Unit)? = null
    private val callback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
            val session = capture?.audioSessionId ?: return
            if (configs?.any { it.clientAudioSessionId == session && it.isClientSilenced } == true) markInterrupted()
        }
    }
    private fun markInterrupted() {
        interrupted = true
        interruption?.invoke()
    }

    @SuppressLint("MissingPermission")
    fun start(dir: File, bitrate: Int, model: DenoiseModel, onInterrupted: (() -> Unit)?): File {
        interruption = onInterrupted
        interrupted = false
        val rawFile = File(dir, "recording.m4a")
        val cleanFile = File(dir, "recording-denoised.m4a.partial")
        val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "手机不支持 16 kHz 录音" }
        val source = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum * 4, 64_000))
            .build()
        var raw: AacPcmSink? = null
        var branch: DenoisingBranch? = null
        try {
            check(source.state == AudioRecord.STATE_INITIALIZED) { "无法初始化麦克风" }
            raw = AacPcmSink(rawFile, bitrate)
            // Failure to load/encode the optional branch must not prevent raw capture.
            var engine: StreamingDenoiser? = null
            try {
                engine = ModelDenoiser(context.assets, model)
                branch = DenoisingBranch(engine, AacPcmSink(cleanFile, bitrate), { warning -> saveWarning(dir, warning) })
                runCatching {
                    File(dir, "denoise-model.txt").writeText(
                        if (model == DenoiseModel.DPDFNET8) "DPDFNET8\nattenuation_limit_db=6\nalignment_delay_samples=640" else model.name,
                    )
                }
            } catch (e: Exception) {
                engine?.close()
                cleanFile.delete()
                saveWarning(dir, e.message ?: "降噪初始化失败，已保留原始录音")
            }
            source.registerAudioRecordingCallback(context.mainExecutor, callback)
            source.startRecording()
            check(source.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            capture = source
            running = true
            val rawSink = raw
            val denoising = branch
            worker = Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                try {
                    val buffer = ShortArray(320)
                    while (running) {
                        val count = source.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        if (count <= 0) {
                            if (running) error("麦克风读取失败：$count")
                            break
                        }
                        val frame = buffer.copyOf(count)
                        rawSink.write(frame)
                        denoising?.accept(frame)
                    }
                } catch (e: Exception) {
                    markInterrupted()
                    saveWarning(dir, "录音中断：${e.message}")
                } finally {
                    try {
                        rawSink.close()
                    } catch (e: Exception) {
                        markInterrupted()
                        saveWarning(dir, "原始录音保存失败：${e.message}")
                    }
                    if (denoising?.finish() == true && !interrupted) {
                        if (!cleanFile.renameTo(File(dir, "recording-denoised.m4a"))) {
                            cleanFile.delete()
                            saveWarning(dir, "降噪录音保存失败，已保留原始录音")
                        }
                    } else {
                        cleanFile.delete()
                    }
                }
            }, "pcm-capture").apply { start() }
            return rawFile
        } catch (e: Exception) {
            running = false
            try {
                source.stop()
            } catch (_: Exception) { }
            source.release()
            try {
                raw?.close()
            } catch (_: Exception) { }
            branch?.finish()
            cleanFile.delete()
            throw e
        }
    }
    fun stop() {
        running = false
        val source = capture ?: return
        try {
            source.stop()
        } catch (_: Exception) {
            markInterrupted()
        }
        worker?.join()
        source.unregisterAudioRecordingCallback(callback)
        source.release()
        capture = null
        worker = null
        interruption = null
    }

    private fun saveWarning(dir: File, text: String) {
        try {
            File(dir, "denoise-warning.txt").writeText(text)
        } catch (_: Exception) { }
    }
}
