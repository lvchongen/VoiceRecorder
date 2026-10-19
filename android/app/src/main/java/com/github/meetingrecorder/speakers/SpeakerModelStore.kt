package com.github.meetingrecorder.speakers

import com.github.meetingrecorder.data.WhisperModel
import com.github.meetingrecorder.data.WhisperModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class SpeakerModelStore(
    private val directory: File,
    private val assets: List<WhisperModel> = FILES,
    downloadUrl: (WhisperModel) -> String = { URLS.getValue(it.id) },
) {
    private val mutex = Mutex()
    private val files = WhisperModelStore(directory, downloadUrl)
    fun installed(): Boolean = assets.all { files.installed(it) }

    suspend fun download(progress: (Int) -> Unit) = mutex.withLock {
        val total = assets.sumOf { it.bytes }
        var completed = 0L
        assets.forEach { asset ->
            if (!files.installed(asset)) files.download(asset) { progress(((completed + asset.bytes * it / 100) * 100 / total).toInt()) }
            completed += asset.bytes
            progress((completed * 100 / total).toInt())
        }
    }

    suspend fun <T> withModels(block: suspend (File, File) -> T): T = mutex.withLock {
        check(installed()) { "请先在设置 → 声纹中下载分人模型" }
        block(files.file(SEGMENTATION), files.file(EMBEDDING))
    }

    suspend fun delete() = withContext(Dispatchers.IO) {
        mutex.withLock { check(!directory.exists() || directory.deleteRecursively()) { "模型删除失败" } }
    }

    companion object {
        const val PIPELINE_VERSION = "pyannote-int8-campplus-zh-en-v2"
        val SEGMENTATION = WhisperModel("segmentation", "segmentation.int8.onnx", 1540506L, "d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d")
        val EMBEDDING = WhisperModel("embedding", "campplus.onnx", 28281164L, "aa3cfc16963a10586a9393f5035d6d6b57e98d358b347f80c2a30bf4f00ceba2")
        val FILES = listOf(SEGMENTATION, EMBEDDING)
        val URLS = mapOf(
            "segmentation" to "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/9403a6902bb58e3d5ae8c7e77c3422de279db2e0/model.int8.onnx",
            "embedding" to "https://huggingface.co/csukuangfj/speaker-embedding-models/resolve/0743f301363dec56491a490f6d6cbc9d67f9a3bf/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx",
        )
        val sizeLabel get() = "${FILES.sumOf { it.bytes } / 1024 / 1024} MiB"
    }
}
