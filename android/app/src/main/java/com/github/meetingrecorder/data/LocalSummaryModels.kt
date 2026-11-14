package com.github.meetingrecorder.data

import java.io.File
import java.util.Locale

enum class LocalChatFormat { QWEN35, CHATML, GEMMA4, PHI4_MINI }

data class LocalSummaryModel(val id: String, val label: String, val repository: String, val revision: String, val asset: WhisperModel, val format: LocalChatFormat) {
    val sizeLabel: String get() = String.format(Locale.ROOT, "%.2f GB", asset.bytes / 1_000_000_000.0)
    val url: String get() = "https://huggingface.co/$repository/resolve/$revision/${asset.filename}"
    companion object {
        val all = listOf(
            LocalSummaryModel("qwen35-2b", "Qwen3.5 2B", "unsloth/Qwen3.5-2B-GGUF", "f6d5376be1edb4d416d56da11e5397a961aca8ae", WhisperModel("qwen35-2b", "Qwen3.5-2B-Q4_K_M.gguf", 1280835840L, "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223"), LocalChatFormat.QWEN35),
            LocalSummaryModel("qwen35-4b", "Qwen3.5 4B", "unsloth/Qwen3.5-4B-GGUF", "e87f176479d0855a907a41277aca2f8ee7a09523", WhisperModel("qwen35-4b", "Qwen3.5-4B-Q4_K_M.gguf", 2740937888L, "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4"), LocalChatFormat.QWEN35),
            LocalSummaryModel("qwen3-4b", "Qwen3 4B Instruct 2507", "unsloth/Qwen3-4B-Instruct-2507-GGUF", "a06e946bb6b655725eafa393f4a9745d460374c9", WhisperModel("qwen3-4b", "Qwen3-4B-Instruct-2507-Q4_K_M.gguf", 2497281120L, "3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597"), LocalChatFormat.CHATML),
            LocalSummaryModel("gemma4-e2b", "Google Gemma 4 E2B", "unsloth/gemma-4-E2B-it-GGUF", "0314792d7f1f7e229411f620751375812bb9faf2", WhisperModel("gemma4-e2b", "gemma-4-E2B-it-Q4_K_M.gguf", 3106738272L, "740185b21d22ceb83a11c3aa62ad5842ef32c70f6096d756bbee85a1e4ec34b8"), LocalChatFormat.GEMMA4),
            LocalSummaryModel("gemma4-e4b", "Google Gemma 4 E4B", "unsloth/gemma-4-E4B-it-GGUF", "bfc15c382204943c3a8fff0c750b94ae2364d7a3", WhisperModel("gemma4-e4b", "gemma-4-E4B-it-Q4_K_M.gguf", 4977171584L, "85a896a047553e842f25297ee5b031d64ff30147d9c4af17b1e4b394cd1fab87"), LocalChatFormat.GEMMA4),
            LocalSummaryModel("phi4-mini", "Microsoft Phi-4 mini Instruct", "unsloth/Phi-4-mini-instruct-GGUF", "78eb92a46fc37e6b524df991ed9aca9bc6aa7b80", WhisperModel("phi4-mini", "Phi-4-mini-instruct-Q4_K_M.gguf", 2491874272L, "88c00229914083cd112853aab84ed51b87bdf6b9ce42f532d8c85c7c63b1730a"), LocalChatFormat.PHI4_MINI),
        )
        const val DEFAULT_ID = "qwen35-4b"
        fun selected(id: String): LocalSummaryModel = all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT_ID }
    }
}

/** Reuse the atomic, SHA-256 verified downloader and lock model deletion during inference. */
class LocalSummaryModelStore(directory: File, private val catalog: List<LocalSummaryModel> = LocalSummaryModel.all, downloadUrl: (LocalSummaryModel) -> String = { it.url }) {
    private val files = WhisperModelStore(directory) { asset -> downloadUrl(catalog.first { it.asset == asset }) }
    fun installed(model: LocalSummaryModel) = files.installed(model.asset)
    suspend fun download(model: LocalSummaryModel, progress: (Int) -> Unit) = files.download(model.asset, progress)
    suspend fun delete(model: LocalSummaryModel) = files.delete(model.asset)
    suspend fun <T> withModel(model: LocalSummaryModel, block: suspend (File) -> T): T {
        check(installed(model)) { "请先在设置中下载所选本地摘要模型：${model.label}" }
        return files.withModel(model.asset, block)
    }
}
