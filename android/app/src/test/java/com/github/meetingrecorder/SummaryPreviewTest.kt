package com.github.meetingrecorder

import android.app.Application
import com.github.meetingrecorder.data.Config
import com.github.meetingrecorder.data.GeminiClient
import com.github.meetingrecorder.data.MeetingProcessor
import com.github.meetingrecorder.data.MeetingRepository
import com.github.meetingrecorder.data.MeetingTemplate
import com.github.meetingrecorder.data.SummaryClient
import com.github.meetingrecorder.data.SummaryDetail
import com.github.meetingrecorder.data.SummaryOptions
import com.github.meetingrecorder.ui.detail.GenState
import com.github.meetingrecorder.ui.detail.MeetingDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = Application::class)
class SummaryPreviewTest {
    @get:Rule val temp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private lateinit var dir: File
    private lateinit var vm: MeetingDetailViewModel
    private var failSummary = false
    private var received: SummaryOptions? = null

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        val config = Config(RuntimeEnvironment.getApplication()).apply { apiKey = "fixture-key" }
        val store = MeetingRepository(temp.newFolder())
        dir = store.createMeetingDir("Fixture")
        File(dir, "transcript.md").writeText("A: We proposed B; no approval yet.")
        File(dir, "notes.md").writeText("Original notes")
        val client = object : SummaryClient {
            override suspend fun summarize(transcript: String, onStatus: (String) -> Unit) = "unused"
            override suspend fun generateTitle(notes: String, onStatus: (String) -> Unit) = "Fixture"
            override suspend fun summarizeWithOptions(transcript: String, options: SummaryOptions, onStatus: (String) -> Unit): String {
                received = options
                if (failSummary) error("network unavailable")
                return "Proposed B; not approved [P1]"
            }
        }
        val processor = MeetingProcessor(GeminiClient(config), store, ioDispatcher = dispatcher, summaryClient = client)
        vm = MeetingDetailViewModel(config, processor, dispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `generation saves immediately and archives prior notes without preview`() = runTest(dispatcher) {
        vm.load(dir.absolutePath)
        advanceUntilIdle()
        val original = File(dir, "notes.md").readText()
        val options = SummaryOptions(MeetingTemplate.TECHNICAL, SummaryDetail.DETAILED, "Chinese")
        vm.regenerateNotes(options)
        advanceUntilIdle()
        assertEquals(options, received)
        assertNull(vm.preview.value)
        assertEquals("Proposed B; not approved [P1]", vm.notes.value)
        assertEquals(options, vm.versions.value.first { it.notes != original }.options)
        assertEquals("Proposed B; not approved [P1]", File(dir, "notes.md").readText())
        assertNull(vm.preview.value)
        assertEquals(2, vm.versions.value.size)
        vm.previewVersion(vm.versions.value.first { it.notes == original })
        vm.adoptPreview()
        advanceUntilIdle()
        assertEquals(original, File(dir, "notes.md").readText())
    }

    @Test
    fun `failed generation never replaces saved notes`() = runTest(dispatcher) {
        vm.load(dir.absolutePath)
        advanceUntilIdle()
        failSummary = true
        vm.regenerateNotes()
        advanceUntilIdle()
        assertTrue(vm.genState.value is GenState.Error)
        assertEquals("Original notes", File(dir, "notes.md").readText())
        assertEquals("Original notes", vm.notes.value)
    }
}
