package com.github.meetingrecorder

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.meetingrecorder.data.MeetingTemplate
import com.github.meetingrecorder.data.SummaryDetail
import com.github.meetingrecorder.data.SummaryOptions
import com.github.meetingrecorder.data.SummaryVersion
import com.github.meetingrecorder.ui.detail.GenState
import com.github.meetingrecorder.ui.detail.MeetingDetailScreen
import com.github.meetingrecorder.ui.detail.MeetingDetailViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SummaryControlsTest {
    @get:Rule val compose = RobolectricComposeRule()

    private fun fixture(preview: SummaryVersion? = null): MeetingDetailViewModel = mock<MeetingDetailViewModel>().also { vm ->
        whenever(vm.defaultSummaryOptions).thenReturn(SummaryOptions())
        whenever(vm.preview).thenReturn(MutableStateFlow(preview))
        whenever(vm.versions).thenReturn(MutableStateFlow(emptyList()))
        whenever(vm.transcript).thenReturn(MutableStateFlow("A: proposed B"))
        whenever(vm.notes).thenReturn(MutableStateFlow(null))
        whenever(vm.hasAudio).thenReturn(MutableStateFlow(false))
        whenever(vm.genState).thenReturn(MutableStateFlow(GenState.Idle))
    }

    @Test
    fun `detail options send temporary template and detail without changing defaults`() {
        val vm = fixture()
        compose.setContent { MaterialTheme { MeetingDetailScreen("/fixture", {}, vm) } }
        compose.onNodeWithText("Generate notes").performClick()
        compose.onNodeWithText("通用会议").performClick()
        compose.onNodeWithText("技术评审").performClick()
        compose.onNodeWithText("标准").performClick()
        compose.onNodeWithText("详细").performClick()
        compose.onNodeWithText("生成预览").performClick()
        verify(vm).regenerateNotes(SummaryOptions(MeetingTemplate.TECHNICAL, SummaryDetail.DETAILED))
    }

    @Test
    fun `preview exposes original evidence and explicit adoption or dismissal`() {
        val vm = fixture(SummaryVersion(notes = "Proposal [P1]", sourceTranscript = "A: proposed B", options = SummaryOptions(), model = "fixture-model"))
        compose.setContent { MaterialTheme { MeetingDetailScreen("/fixture", {}, vm) } }
        compose.onNodeWithText("纪要预览").assertIsDisplayed()
        compose.onNodeWithText("查看引用原文").performClick()
        compose.onNodeWithText("返回纪要").assertIsDisplayed()
        compose.onNodeWithText("返回纪要").performClick()
        compose.onNodeWithText("采用此版本").performClick()
        verify(vm).adoptPreview()
        compose.onNodeWithText("保留原版").performClick()
        verify(vm).dismissPreview()
    }
}
