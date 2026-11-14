package com.github.meetingrecorder.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@Serializable
data class SummaryVersion(
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val notes: String,
    val sourceTranscript: String? = null,
    val options: SummaryOptions? = null,
    val provider: String? = null,
    val model: String? = null,
    val prompt: String? = null,
    val templateVersion: Int = 1,
)

private val historyJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/** Append-only snapshots; notes.md remains the interoperable, currently adopted version. */
class SummaryHistory {
    fun list(dir: File): List<SummaryVersion> = File(dir, "notes-history").listFiles().orEmpty()
        .filter { it.extension == "json" }
        .mapNotNull { file -> runCatching { historyJson.decodeFromString(SummaryVersion.serializer(), file.readText()) }.getOrNull() }
        .sortedByDescending { it.createdAt }

    fun record(dir: File, version: SummaryVersion) {
        require(version.notes.isNotBlank()) { "Cannot save empty meeting notes." }
        require(version.id.matches(Regex("[a-zA-Z0-9-]+"))) { "Invalid summary version ID." }
        val history = File(dir, "notes-history")
        check(history.isDirectory || history.mkdirs()) { "Could not create summary history." }
        val target = File(history, "${version.id}.json")
        if (target.exists()) return
        atomicText(target, historyJson.encodeToString(SummaryVersion.serializer(), version))
    }

    fun adopt(dir: File, version: SummaryVersion) {
        require(version.notes.isNotBlank()) { "Cannot save empty meeting notes." }
        val target = File(dir, "notes.md")
        val previous = target.takeIf { it.exists() }?.readText()
        // Archive legacy notes too. A snapshot failure must leave the current notes untouched.
        if (!previous.isNullOrBlank() && list(dir).none { it.notes == previous }) {
            record(dir, SummaryVersion(notes = previous))
        }
        record(dir, version)
        atomicText(target, version.notes)
    }
}

internal fun atomicText(target: File, text: String) {
    val temporary = File.createTempFile(".${target.name}", ".tmp", target.parentFile)
    try {
        FileOutputStream(temporary).use {
            it.write(text.toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        check(temporary.renameTo(target)) { "Could not save ${target.name}" }
    } finally {
        temporary.delete()
    }
}
