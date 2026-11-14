package com.github.meetingrecorder

import android.app.Application
import android.media.MediaPlayer
import com.github.meetingrecorder.data.Config
import com.github.meetingrecorder.data.GeminiClient
import com.github.meetingrecorder.data.MeetingProcessor
import com.github.meetingrecorder.data.MeetingRepository
import com.github.meetingrecorder.ui.detail.MeetingDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.mockConstruction
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = Application::class)
class AudioSeekingTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `paused seeking clamps bounds and repeated skips use requested position`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            mockConstruction(MediaPlayer::class.java) { player, _ -> whenever(player.duration).thenReturn(60_000) }.use { players ->
                val config = Config(RuntimeEnvironment.getApplication())
                val repo = MeetingRepository(temp.newFolder())
                val dir = repo.createMeetingDir("Audio fixture")
                File(dir, "recording.m4a").writeBytes(byteArrayOf(0))
                val processor = MeetingProcessor(GeminiClient(config), repo, ioDispatcher = dispatcher)
                val vm = MeetingDetailViewModel(config, processor, dispatcher, waveformReader = { _, _ -> emptyList() })
                vm.load(dir.absolutePath)
                advanceUntilIdle()
                assertEquals(60_000, vm.durationMs.value)
                vm.seekTo(30_000)
                verify(players.constructed().single()).seekTo(30_000L, MediaPlayer.SEEK_CLOSEST)
                assertEquals(30_000, vm.positionMs.value)
                vm.skipBy(10_000)
                vm.skipBy(10_000)
                assertEquals(50_000, vm.positionMs.value)
                vm.seekTo(Int.MAX_VALUE)
                assertEquals(60_000, vm.positionMs.value)
                vm.seekTo(-100)
                assertEquals(0, vm.positionMs.value)
                assertFalse(vm.isPlaying.value)
                verify(players.constructed().single(), never()).start()
                vm.playPause()
                vm.seekTo(15_000)
                assertEquals(true, vm.isPlaying.value)
                verify(players.constructed().single()).start()
                val completion = argumentCaptor<MediaPlayer.OnCompletionListener>()
                verify(players.constructed().single()).setOnCompletionListener(completion.capture())
                completion.firstValue.onCompletion(players.constructed().single())
                assertEquals(60_000, vm.positionMs.value)
                assertFalse(vm.isPlaying.value)
                vm.stop()
                assertEquals(0, vm.positionMs.value)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
