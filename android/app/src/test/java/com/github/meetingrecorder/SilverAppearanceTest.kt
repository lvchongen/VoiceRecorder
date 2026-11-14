package com.github.meetingrecorder

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.LocalView
import com.github.meetingrecorder.data.Meeting
import com.github.meetingrecorder.data.SummaryOptions
import com.github.meetingrecorder.ui.detail.GenState
import com.github.meetingrecorder.ui.detail.MeetingDetailScreen
import com.github.meetingrecorder.ui.detail.MeetingDetailViewModel
import com.github.meetingrecorder.ui.meetings.MeetingsScreen
import com.github.meetingrecorder.ui.meetings.MeetingsViewModel
import com.github.meetingrecorder.ui.theme.MeetingRecorderTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDateTime

/** Renders real Compose screens using synthetic meetings, never the user's recordings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SilverAppearanceTest {
    @get:Rule val compose = RobolectricComposeRule()
    private lateinit var contentView: View

    @Test fun darkLibrary() = library(true)

    @Test fun lightLibrary() = library(false)

    @Test fun darkPlayer() = player(true)

    @Test fun lightPlayer() = player(false)

    private fun library(dark: Boolean) {
        val vm = mock<MeetingsViewModel>()
        val first = Meeting(File("fixture-a"), "10:30", LocalDateTime.now().withHour(10).withMinute(30), "产品周会", true, true, true, 2210)
        whenever(vm.meetings).thenReturn(MutableStateFlow(listOf(first, first.copy(path = File("fixture-b"), title = "客户需求沟通", hasNotes = false, hasTranscript = false, durationSeconds = 1458), first.copy(path = File("fixture-c"), title = "技术方案评审", date = first.date.minusDays(1), durationSeconds = 3126))))
        whenever(vm.isLoading).thenReturn(MutableStateFlow(false))
        whenever(vm.errorMessage).thenReturn(MutableStateFlow(null))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            contentView = LocalView.current
            MeetingRecorderTheme(dark) { MeetingsScreen({}, {}, onRecord = {}, onImport = {}, onSettings = {}, viewModel = vm) }
        }
        save("library", dark)
    }

    private fun player(dark: Boolean) {
        val vm = mock<MeetingDetailViewModel>()
        whenever(vm.defaultSummaryOptions).thenReturn(SummaryOptions())
        whenever(vm.preview).thenReturn(MutableStateFlow(null))
        whenever(vm.versions).thenReturn(MutableStateFlow(emptyList()))
        whenever(vm.transcript).thenReturn(MutableStateFlow("Synthetic transcript"))
        whenever(vm.notes).thenReturn(MutableStateFlow("Synthetic notes"))
        whenever(vm.hasAudio).thenReturn(MutableStateFlow(true))
        whenever(vm.hasOriginal).thenReturn(MutableStateFlow(true))
        whenever(vm.usingOriginal).thenReturn(MutableStateFlow(false))
        whenever(vm.audioWarning).thenReturn(MutableStateFlow(null))
        whenever(vm.genState).thenReturn(MutableStateFlow(GenState.Idle))
        whenever(vm.isPlaying).thenReturn(MutableStateFlow(true))
        whenever(vm.positionMs).thenReturn(MutableStateFlow(754_000))
        whenever(vm.durationMs).thenReturn(MutableStateFlow(2_210_000))
        whenever(vm.waveform).thenReturn(MutableStateFlow(List(72) { ((it * 17 % 43) + 8) / 55f }))
        whenever(vm.displayTitle).thenReturn(MutableStateFlow("产品周会"))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            contentView = LocalView.current
            MeetingRecorderTheme(dark) { MeetingDetailScreen("/synthetic", {}, vm) }
        }
        save("player", dark)
    }

    private fun save(screen: String, dark: Boolean) {
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle {
            contentView.measure(View.MeasureSpec.makeMeasureSpec(822, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1782, View.MeasureSpec.EXACTLY))
            contentView.layout(0, 0, 822, 1782)
        }
        compose.mainClock.advanceTimeBy(1000)
        val directory = File("build/reports/silver-ui").apply { mkdirs() }
        File(directory, "$screen-${if (dark) "dark" else "light"}.png").outputStream().use {
            val bitmap = Bitmap.createBitmap(contentView.width, contentView.height, Bitmap.Config.ARGB_8888)
            compose.runOnIdle { contentView.draw(Canvas(bitmap)) }
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
