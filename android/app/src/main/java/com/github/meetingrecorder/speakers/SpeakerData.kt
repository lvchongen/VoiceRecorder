package com.github.meetingrecorder.speakers

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.sqrt

@Serializable
data class VoiceSample(
    val id: String = UUID.randomUUID().toString(),
    val embedding: List<Float>,
    val model: String,
    val source: String,
    val startMs: Long,
    val durationMs: Long,
)

@Serializable
data class MeetingSpeaker(
    val label: String,
    val name: String? = null,
    val profileId: String? = null,
    val samples: List<VoiceSample> = emptyList(),
)

@Serializable
data class SpeakerTurn(val startMs: Long, val endMs: Long, val label: String?)

@Serializable
data class SpeakerMeeting(
    val id: String = UUID.randomUUID().toString(),
    val sourceName: String,
    val sourceHash: String,
    val speakers: List<MeetingSpeaker>,
    val turns: List<SpeakerTurn>,
    val ownerPath: String = "",
    val pipelineVersion: String = SpeakerModelStore.PIPELINE_VERSION,
)

@Serializable
data class VoiceProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val samples: List<VoiceSample> = emptyList(),
)

data class VoiceCandidate(val profile: VoiceProfile, val similarity: Float)

fun speakerLabel(index: Int): String {
    require(index >= 0)
    var n = index + 1
    var label = ""
    while (n > 0) {
        n--
        label = ('A'.code + n % 26).toChar() + label
        n /= 26
    }
    return label
}

fun normalizedEmbedding(values: List<Float>): List<Float>? {
    if (values.isEmpty() || values.any { !it.isFinite() }) return null
    val norm = sqrt(values.sumOf { it.toDouble() * it }).toFloat()
    if (!norm.isFinite() || norm < 1e-6f) return null
    return values.map { it / norm }
}

fun cosineSimilarity(a: List<Float>, b: List<Float>): Float? {
    if (a.size != b.size) return null
    val left = normalizedEmbedding(a) ?: return null
    val right = normalizedEmbedding(b) ?: return null
    return left.indices.sumOf { (left[it] * right[it]).toDouble() }.toFloat().coerceIn(-1f, 1f)
}

/** Scores are suggestions, never identity probabilities or automatic associations. */
fun voiceCandidates(samples: List<VoiceSample>, profiles: List<VoiceProfile>): List<VoiceCandidate> = profiles.mapNotNull { profile ->
    val queryScores = samples.mapNotNull { query ->
        val scores = profile.samples.filter { it.model == query.model }.mapNotNull { cosineSimilarity(query.embedding, it.embedding) }.sortedDescending()
        // Keep separate prototypes for changed voice states. A sole high match is still only a suggestion.
        scores.take(2).takeIf { it.isNotEmpty() }?.average()?.toFloat()
    }
    if (queryScores.isEmpty()) null else VoiceCandidate(profile, queryScores.average().toFloat())
}.sortedByDescending { it.similarity }.take(3)

/** Partition overlapped regions explicitly instead of attributing simultaneous speech to one person. */
fun exclusiveTurns(turns: List<SpeakerTurn>): List<SpeakerTurn> {
    val valid = turns.filter { it.startMs >= 0 && it.endMs > it.startMs }
    val boundaries = valid.flatMap { listOf(it.startMs, it.endMs) }.distinct().sorted()
    val output = mutableListOf<SpeakerTurn>()
    boundaries.zipWithNext().forEach { (start, end) ->
        val active = valid.filter { it.startMs < end && it.endMs > start }
        if (active.isEmpty()) return@forEach
        val labels = active.map { it.label }.distinct()
        val label = if (labels.size == 1) labels.single() else null
        val previous = output.lastOrNull()
        if (previous?.label == label && previous?.endMs == start) {
            output[output.lastIndex] = previous.copy(endMs = end)
        } else {
            output += SpeakerTurn(start, end, label)
        }
    }
    return output
}

fun sampleQuality(samples: FloatArray): Boolean {
    if (samples.size < 2 * 16000 || samples.any { !it.isFinite() }) return false
    val rms = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
    val clipped = samples.count { kotlin.math.abs(it) >= 0.99f }.toDouble() / samples.size
    return rms >= 0.005 && clipped < 0.02
}

/** Remove ASR turn decoration while preserving bold words that belong to the sentence. */
fun cleanSpeakerTurnText(line: String): String = line
    .replace(Regex("^\\s*\\[\\d{2}:\\d{2}:\\d{2}\\]\\s*"), "")
    .replace(Regex("^\\s*\\*\\*[^*\\n]{1,80}(?:[:：]\\*\\*|\\*\\*[:：])\\s*"), "")
    .trim()
