package com.github.meetingrecorder

import com.github.meetingrecorder.speakers.SpeakerTurn
import com.github.meetingrecorder.speakers.VoiceProfile
import com.github.meetingrecorder.speakers.VoiceSample
import com.github.meetingrecorder.speakers.cosineSimilarity
import com.github.meetingrecorder.speakers.exclusiveTurns
import com.github.meetingrecorder.speakers.normalizedEmbedding
import com.github.meetingrecorder.speakers.sampleQuality
import com.github.meetingrecorder.speakers.speakerLabel
import com.github.meetingrecorder.speakers.voiceCandidates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerDataTest {
    @Test fun `turn decoration removal preserves bold technical content`() {
        assertEquals("你好", com.github.meetingrecorder.speakers.cleanSpeakerTurnText("[00:00:02] **A:** 你好"))
        assertEquals("你好", com.github.meetingrecorder.speakers.cleanSpeakerTurnText("**A**: 你好"))
        assertEquals("**Kubernetes** 已部署", com.github.meetingrecorder.speakers.cleanSpeakerTurnText("[00:00:02] **Kubernetes** 已部署"))
    }

    @Test fun `meeting labels continue after Z`() {
        assertEquals(listOf("A", "B", "Z", "AA", "AB"), listOf(0, 1, 25, 26, 27).map(::speakerLabel))
    }

    @Test fun `invalid embeddings and different dimensions cannot match`() {
        assertNull(cosineSimilarity(listOf(1f), listOf(1f, 0f)))
        assertNull(cosineSimilarity(listOf(0f, 0f), listOf(1f, 0f)))
        assertNull(normalizedEmbedding(listOf(Float.NaN)))
        assertNull(normalizedEmbedding(listOf(Float.POSITIVE_INFINITY)))
        assertEquals(1f, cosineSimilarity(listOf(3f, 4f), listOf(6f, 8f))!!, 0.00001f)
    }

    private fun sample(vector: List<Float>, model: String = "test") = VoiceSample(embedding = vector, model = model, source = "fixture", startMs = 0, durationMs = 4000)

    @Test fun `multiple confirmed samples remain separate and incompatible models are excluded`() {
        val normal = sample(listOf(1f, 0f))
        val changed = sample(listOf(0.8f, 0.6f))
        val profile = VoiceProfile(name = "张三", samples = listOf(normal, changed))
        val other = VoiceProfile(name = "李四", samples = listOf(sample(listOf(-1f, 0f))))
        val incompatible = VoiceProfile(name = "旧模型", samples = listOf(sample(listOf(1f, 0f), "other-model")))
        val result = voiceCandidates(listOf(changed), listOf(other, profile, incompatible))
        assertEquals(profile.id, result.first().profile.id)
        assertEquals(2, result.size)
        assertEquals(2, profile.samples.size)
        assertTrue(voiceCandidates(emptyList(), listOf(profile)).isEmpty())
    }

    @Test fun `overlapping speech is explicit and never becomes a clean enrollment sample`() {
        val turns = exclusiveTurns(listOf(SpeakerTurn(0, 4000, "0"), SpeakerTurn(2000, 6000, "1"), SpeakerTurn(8000, 9000, "1")))
        assertEquals(listOf(SpeakerTurn(0, 2000, "0"), SpeakerTurn(2000, 4000, null), SpeakerTurn(4000, 6000, "1"), SpeakerTurn(8000, 9000, "1")), turns)
        assertTrue(exclusiveTurns(listOf(SpeakerTurn(-1, 1, "x"), SpeakerTurn(2, 1, "x"))).isEmpty())
    }

    @Test fun `short silent clipped and invalid clips cannot enroll`() {
        assertFalse(sampleQuality(FloatArray(16000) { 0.1f }))
        assertFalse(sampleQuality(FloatArray(48000)))
        assertFalse(sampleQuality(FloatArray(48000) { 1f }))
        assertFalse(sampleQuality(FloatArray(48000) { Float.NaN }))
        assertTrue(sampleQuality(FloatArray(48000) { if (it % 2 == 0) 0.1f else -0.1f }))
    }
}
