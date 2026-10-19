package com.github.meetingrecorder

import com.github.meetingrecorder.speakers.MeetingSpeaker
import com.github.meetingrecorder.speakers.SpeakerMeeting
import com.github.meetingrecorder.speakers.SpeakerModelStore
import com.github.meetingrecorder.speakers.SpeakerRepository
import com.github.meetingrecorder.speakers.SpeakerTurn
import com.github.meetingrecorder.speakers.VoiceSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SpeakerRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private fun fixture(repository: SpeakerRepository, dir: File, label: String = "A"): SpeakerMeeting {
        val sample = VoiceSample(embedding = listOf(1f, 0f), model = SpeakerModelStore.EMBEDDING.sha256, source = dir.name, startMs = 1000, durationMs = 3000)
        val meeting = SpeakerMeeting(sourceName = "recording.m4a", sourceHash = "hash", speakers = listOf(MeetingSpeaker(label, samples = listOf(sample))), turns = listOf(SpeakerTurn(0, 4000, label)))
        repository.analysisDirectory(meeting.id).mkdirs()
        repository.meetingSample(meeting, sample).writeBytes(byteArrayOf(1, 2, 3))
        repository.saveMeeting(dir, meeting)
        return meeting
    }

    @Test fun `automatic enrollment matches across meetings and respects deletion`() {
        val repo = SpeakerRepository(temp.newFolder())
        val first = temp.newFolder()
        fixture(repo, first)
        val beforeEnrollment = repo.profileChanges.value
        val a = repo.autoEnroll(first)
        assertTrue(repo.profileChanges.value > beforeEnrollment)
        val profile = repo.profiles().single()
        assertEquals("未命名 1", profile.name)
        assertEquals(profile.id, a.speakers.single().profileId)
        repo.renameProfile(profile.id, "张三")
        val next = temp.newFolder()
        fixture(repo, next)
        val b = repo.autoEnroll(next)
        assertEquals(profile.id, b.speakers.single().profileId)
        assertEquals("张三", b.speakers.single().name)
        assertEquals(2, repo.profiles().single().samples.size)
        repo.autoEnroll(next)
        assertEquals(2, repo.profiles().single().samples.size)
        val beforeDeletion = repo.profileChanges.value
        repo.deleteProfile(profile.id)
        assertTrue(repo.profileChanges.value > beforeDeletion)
        repo.autoEnroll(next)
        assertTrue(repo.profiles().isEmpty())
    }

    @Test fun `manual names remain per meeting and never enroll implicitly`() {
        val repo = SpeakerRepository(temp.newFolder())
        val a = temp.newFolder()
        val b = temp.newFolder()
        fixture(repo, a)
        fixture(repo, b)
        repo.confirm(a, "A", "张三")
        assertEquals("张三", repo.readMeeting(a)!!.speakers.single().name)
        assertNull(repo.readMeeting(b)!!.speakers.single().name)
        assertTrue(repo.profiles().isEmpty())
        assertFalse(File(a, "speakers.json").readText().contains("embedding"))
        assertEquals(listOf("speakers.json"), a.listFiles()!!.map { it.name })
    }

    @Test fun `confirmed profiles copy independent samples and updates only add selected clips`() {
        val repo = SpeakerRepository(temp.newFolder())
        val a = temp.newFolder()
        val b = temp.newFolder()
        val first = fixture(repo, a)
        val second = fixture(repo, b)
        repo.confirm(a, "A", "张三", saveVoiceprint = true)
        val profile = repo.profiles().single()
        repo.confirm(b, "A", "ignored", profile.id)
        assertEquals(1, repo.profiles().single().samples.size)
        assertEquals("张三", repo.readMeeting(b)!!.speakers.single().name)
        repo.confirm(b, "A", "张三", profile.id, saveVoiceprint = true, sampleIds = second.speakers.single().samples.map { it.id }.toSet())
        assertEquals(2, repo.profiles().single().samples.size)
        repo.deleteMeetingSamples(a)
        assertTrue(repo.profileSample(repo.profiles().single(), first.speakers.single().samples.single()).isFile)
        repo.removeSample(profile.id, first.speakers.single().samples.single().id)
        assertEquals(1, repo.profiles().single().samples.size)
        assertFalse(repo.profileSample(profile, first.speakers.single().samples.single()).exists())
        repo.renameProfile(profile.id, "张老师")
        assertEquals("张老师", repo.profiles().single().name)
        repo.deleteProfile(profile.id)
        assertTrue(repo.profiles().isEmpty())
        assertEquals("张三", repo.readMeeting(b)!!.speakers.single().name)
        repo.clearName(b, "A")
        assertNull(repo.readMeeting(b)!!.speakers.single().profileId)
        assertNull(repo.readMeeting(b)!!.speakers.single().name)
    }

    @Test fun `failed enrollments preserve earlier profile and meeting associations`() {
        val repo = SpeakerRepository(temp.newFolder())
        val dir = temp.newFolder()
        val state = fixture(repo, dir)
        repo.meetingSample(state, state.speakers.single().samples.single()).delete()
        assertTrue(runCatching { repo.confirm(dir, "A", "张三", saveVoiceprint = true) }.isFailure)
        assertTrue(repo.profiles().isEmpty())
        assertNull(repo.readMeeting(dir)!!.speakers.single().name)
        assertTrue(runCatching { repo.confirm(dir, "A", "张三", existingProfileId = "missing") }.isFailure)
        assertTrue(runCatching { repo.confirm(dir, "A", " ") }.isFailure)
        assertTrue(runCatching { repo.analysisDirectory("../../escape") }.isFailure)
    }

    @Test fun `copying a meeting does not share identities but renaming preserves them`() {
        val repo = SpeakerRepository(temp.newFolder())
        val first = temp.newFolder()
        fixture(repo, first)
        repo.confirm(first, "A", "张三")
        val copied = temp.newFolder()
        File(first, "speakers.json").copyTo(File(copied, "speakers.json"))
        assertNull(repo.readMeeting(copied))
        val moved = File(first.parentFile, first.name + "-renamed")
        assertTrue(first.renameTo(moved))
        assertEquals("张三", repo.readMeeting(moved)!!.speakers.single().name)
    }

    @Test fun `clearing meeting analysis preserves original content and independently enrolled copies`() {
        val repo = SpeakerRepository(temp.newFolder())
        val dir = temp.newFolder()
        val meeting = fixture(repo, dir)
        File(dir, "recording.m4a").writeText("original audio")
        File(dir, "transcript.md").writeText("original transcript")
        repo.confirm(dir, "A", "张三", saveVoiceprint = true)
        val profile = repo.profiles().single()
        repo.clearMeeting(dir)
        assertNull(repo.readMeeting(dir))
        assertFalse(repo.analysisDirectory(meeting.id).exists())
        assertEquals("original audio", File(dir, "recording.m4a").readText())
        assertEquals("original transcript", File(dir, "transcript.md").readText())
        assertTrue(repo.profileSample(profile, profile.samples.single()).exists())
    }
}
