package com.github.meetingrecorder

import com.github.meetingrecorder.data.Config
import com.github.meetingrecorder.speakers.MeetingSpeaker
import com.github.meetingrecorder.speakers.SpeakerMeeting
import com.github.meetingrecorder.speakers.SpeakerModelStore
import com.github.meetingrecorder.speakers.SpeakerRepository
import com.github.meetingrecorder.speakers.SpeakerService
import com.github.meetingrecorder.speakers.SpeakerViewModel
import com.github.meetingrecorder.speakers.VoiceSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class RecordedVoiceProfileTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `named recordings create independent profiles and release temporary audio`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = SpeakerRepository(temp.newFolder())
            val service = mock<SpeakerService>()
            whenever(service.models).thenReturn(SpeakerModelStore(temp.newFolder(), assets = emptyList()))
            whenever(service.analyze(any(), any(), any())).thenAnswer { invocation ->
                val directory = invocation.getArgument<File>(1)
                val sample = VoiceSample(embedding = listOf(1f, 0f), model = SpeakerModelStore.EMBEDDING.sha256, source = "test", startMs = 0, durationMs = 3000)
                val meeting = SpeakerMeeting(sourceName = "recording.m4a", sourceHash = "hash", speakers = listOf(MeetingSpeaker("A", samples = listOf(sample))), turns = emptyList())
                repository.analysisDirectory(meeting.id).mkdirs()
                repository.meetingSample(meeting, sample).writeBytes(byteArrayOf(1, 2, 3))
                repository.saveMeeting(directory, meeting)
                meeting
            }
            val viewModel = SpeakerViewModel(service, repository, mock<Config>(), dispatcher)
            for (name in listOf(" 张三 ", "李四")) {
                val directory = temp.newFolder()
                val audio = File(directory, "recording.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
                viewModel.addRecordedVoiceSample(audio, name)
                advanceUntilIdle()
                assertNull(viewModel.state.value.error)
                assertFalse(directory.exists())
            }
            // Even identical embeddings must not override the name/identity explicitly entered.
            val profiles = repository.profiles()
            assertEquals(setOf("张三", "李四"), profiles.map { it.name }.toSet())
            assertEquals(2, profiles.map { it.id }.toSet().size)
            profiles.forEach { profile ->
                assertEquals(1, profile.samples.size)
                assertEquals(3L, repository.profileSample(profile, profile.samples.single()).length())
            }
            val invalidDirectory = temp.newFolder()
            val invalid = File(invalidDirectory, "recording.m4a").apply { writeText("fixture") }
            viewModel.addRecordedVoiceSample(invalid, " ")
            advanceUntilIdle()
            assertNotNull(viewModel.state.value.error)
            assertFalse(invalidDirectory.exists())
            assertEquals(2, repository.profiles().size)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
