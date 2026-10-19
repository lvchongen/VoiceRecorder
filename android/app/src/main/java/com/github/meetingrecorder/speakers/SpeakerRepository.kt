package com.github.meetingrecorder.speakers

import com.github.meetingrecorder.data.atomicText
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

private val speakerJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

@Serializable
private data class MeetingSpeakerLink(val id: String)

/** Biometrics and audition clips stay in app-private, backup-excluded storage. */
class SpeakerRepository(private val root: File) {
    private val _profileChanges = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val profileChanges = _profileChanges.asStateFlow()

    private fun identifier(id: String): String {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}"))) { "无效的声纹标识" }
        return id
    }
    fun analysisDirectory(id: String) = File(root, "meetings/${identifier(id)}")
    private fun profileDirectory(id: String) = File(root, "profiles/${identifier(id)}")
    fun meetingSample(meeting: SpeakerMeeting, sample: VoiceSample) = File(analysisDirectory(meeting.id), "${identifier(sample.id)}.wav")
    fun profileSample(profile: VoiceProfile, sample: VoiceSample) = File(profileDirectory(profile.id), "${identifier(sample.id)}.wav")

    @Synchronized fun readMeeting(dir: File): SpeakerMeeting? {
        val link = File(dir, "speakers.json")
        if (!link.exists()) return null
        val id = speakerJson.decodeFromString(MeetingSpeakerLink.serializer(), link.readText()).id
        val file = File(analysisDirectory(id), "state.json")
        if (!file.exists()) return null
        val state = speakerJson.decodeFromString(SpeakerMeeting.serializer(), file.readText())
        check(state.id == id) { "说话人分析文件不匹配" }
        if (state.ownerPath.isNotEmpty() && state.ownerPath != dir.canonicalPath) {
            if (File(state.ownerPath).exists()) return null // A copied meeting must get its own identities.
            val moved = state.copy(ownerPath = dir.canonicalPath)
            atomicText(file, speakerJson.encodeToString(SpeakerMeeting.serializer(), moved))
            return moved
        }
        return state
    }

    @Synchronized fun saveMeeting(dir: File, meeting: SpeakerMeeting) {
        val destination = analysisDirectory(meeting.id)
        check(destination.isDirectory || destination.mkdirs()) { "无法保存说话人分析" }
        atomicText(File(destination, "state.json"), speakerJson.encodeToString(SpeakerMeeting.serializer(), meeting.copy(ownerPath = dir.canonicalPath)))
        atomicText(File(dir, "speakers.json"), speakerJson.encodeToString(MeetingSpeakerLink.serializer(), MeetingSpeakerLink(meeting.id)))
    }

    @Synchronized fun deleteMeetingSamples(dir: File) {
        val meeting = readMeeting(dir) ?: return
        val samples = analysisDirectory(meeting.id)
        check(!samples.exists() || samples.deleteRecursively()) { "会议试听样本删除失败" }
    }

    @Synchronized fun clearMeeting(dir: File) {
        deleteMeetingSamples(dir)
        val link = File(dir, "speakers.json")
        check(!link.exists() || link.delete()) { "本场分析引用删除失败" }
    }

    @Synchronized fun removeAnalysis(id: String) {
        val directory = analysisDirectory(id)
        check(!directory.exists() || directory.deleteRecursively()) { "旧分析样本清理失败" }
    }

    @Synchronized fun profiles(): List<VoiceProfile> = File(root, "profiles").listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
        runCatching {
            val profile = speakerJson.decodeFromString(VoiceProfile.serializer(), File(dir, "profile.json").readText())
            check(profile.id == dir.name)
            profile
        }.getOrNull()
    }.sortedBy { it.name }

    private fun saveProfile(profile: VoiceProfile) {
        val name = profile.name.trim()
        require(name.isNotEmpty() && name.length <= 80) { "姓名需为 1–80 个字符" }
        val dir = profileDirectory(profile.id)
        check(dir.isDirectory || dir.mkdirs()) { "无法创建声纹档案" }
        atomicText(File(dir, "profile.json"), speakerJson.encodeToString(VoiceProfile.serializer(), profile.copy(name = name)))
        _profileChanges.value += 1
    }

    /** Match conservatively; enroll clean unmatched voices without adding UI steps. */
    @Synchronized fun autoEnroll(dir: File): SpeakerMeeting {
        var meeting = requireNotNull(readMeeting(dir))
        val used = meeting.speakers.mapNotNull { it.profileId }.toMutableSet()
        for (speaker in meeting.speakers) {
            // A deleted profile stays deleted; rereading a cached meeting must not recreate it.
            if (speaker.profileId != null) continue
            val samples = speaker.samples.filter {
                it.model == SpeakerModelStore.EMBEDDING.sha256 && normalizedEmbedding(it.embedding) != null && meetingSample(meeting, it).isFile
            }
            if (samples.isEmpty()) continue
            val profiles = profiles()
            val candidates = voiceCandidates(samples, profiles.filter { it.id !in used })
            val best = candidates.firstOrNull()
            val match = best?.takeIf { it.similarity >= 0.8f && it.similarity - (candidates.getOrNull(1)?.similarity ?: -1f) >= 0.1f }?.profile
            val name = match?.name ?: generateSequence(1) { it + 1 }.map { "未命名 $it" }.first { name -> profiles.none { it.name == name } }
            meeting = confirm(dir, speaker.label, name, match?.id, saveVoiceprint = match == null || match.samples.size < 20, sampleIds = setOf(samples.first().id))
            meeting.speakers.first { it.label == speaker.label }.profileId?.let(used::add)
        }
        return meeting
    }

    /** Copy clips so profile samples survive meeting deletion. */
    @Synchronized fun confirm(
        dir: File,
        label: String,
        name: String,
        existingProfileId: String? = null,
        saveVoiceprint: Boolean = false,
        sampleIds: Set<String>? = null,
    ): SpeakerMeeting {
        val meeting = requireNotNull(readMeeting(dir)) { "请先分析本场会议" }
        val speaker = meeting.speakers.first { it.label == label }
        val cleanName = name.trim()
        require(cleanName.isNotEmpty() && cleanName.length <= 80) { "姓名需为 1–80 个字符" }
        var profile = existingProfileId?.let { id -> profiles().firstOrNull { it.id == id } ?: error("声纹档案已删除，请重新选择") }
        if (saveVoiceprint) {
            val selectedSamples = speaker.samples.filter { sampleIds == null || it.id in sampleIds }
            check(selectedSamples.isNotEmpty()) { "没有可用的清晰样本；可仅为本场会议命名" }
            profile = profile ?: VoiceProfile(name = cleanName)
            val missing = selectedSamples.filter { sample -> profile!!.samples.none { it.id == sample.id } }
            check(profile.samples.size + missing.size <= 20) { "每人最多保留 20 份样本，请先移除旧样本" }
            val destination = profileDirectory(profile.id)
            check(destination.isDirectory || destination.mkdirs())
            val copied = mutableListOf<File>()
            try {
                missing.forEach { sample ->
                    require(normalizedEmbedding(sample.embedding) != null && sample.model == SpeakerModelStore.EMBEDDING.sha256) { "声纹模型不兼容，请重新分析" }
                    val target = profileSample(profile, sample)
                    check(!target.exists()) { "样本已存在，请刷新后重试" }
                    copied += target
                    meetingSample(meeting, sample).copyTo(target)
                }
                profile = profile.copy(samples = profile.samples + missing)
                saveProfile(profile)
            } catch (e: Exception) {
                copied.forEach { it.delete() }
                throw e
            }
        }
        val updated = meeting.copy(
            speakers = meeting.speakers.map {
                if (it.label == label) it.copy(name = profile?.name ?: cleanName, profileId = profile?.id) else it
            },
        )
        saveMeeting(dir, updated)
        return updated
    }

    @Synchronized fun clearName(dir: File, label: String): SpeakerMeeting {
        val meeting = requireNotNull(readMeeting(dir))
        val updated = meeting.copy(speakers = meeting.speakers.map { if (it.label == label) it.copy(name = null, profileId = null) else it })
        saveMeeting(dir, updated)
        return updated
    }

    @Synchronized fun renameProfile(id: String, name: String) {
        saveProfile(profiles().first { it.id == id }.copy(name = name))
    }

    @Synchronized fun removeSample(profileId: String, sampleId: String) {
        val profile = profiles().first { it.id == profileId }
        val sample = profile.samples.first { it.id == sampleId }
        val file = profileSample(profile, sample)
        check(!file.exists() || file.delete()) { "样本文件删除失败" }
        saveProfile(profile.copy(samples = profile.samples.filterNot { it.id == sampleId }))
    }

    @Synchronized fun deleteProfile(id: String) {
        val dir = profileDirectory(id)
        check(!dir.exists() || dir.deleteRecursively()) { "声纹删除未完成，请重试" }
        _profileChanges.value += 1
    }
}
