package com.github.meetingrecorder

import com.github.meetingrecorder.data.MeetingTemplate
import com.github.meetingrecorder.data.SummaryHistory
import com.github.meetingrecorder.data.SummaryOptions
import com.github.meetingrecorder.data.SummaryVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SummaryHistoryTest {
    @get:Rule val temp = TemporaryFolder()
    private val history = SummaryHistory()

    @Test
    fun `adoption archives legacy notes and restores without losing versions`() {
        val dir = temp.newFolder()
        File(dir, "notes.md").writeText("Original notes")
        val next = SummaryVersion(notes = "New notes", options = SummaryOptions(template = MeetingTemplate.TECHNICAL), provider = "GEMINI", model = "test-model")
        history.adopt(dir, next)
        assertEquals("New notes", File(dir, "notes.md").readText())
        assertEquals(2, history.list(dir).size)
        assertEquals(next, history.list(dir).first { it.id == next.id })
        val original = history.list(dir).first { it.notes == "Original notes" }
        history.adopt(dir, original)
        assertEquals("Original notes", File(dir, "notes.md").readText())
        assertEquals(2, history.list(dir).size)
    }

    @Test
    fun `empty version and archive failure leave current notes intact`() {
        val dir = temp.newFolder()
        File(dir, "notes.md").writeText("Keep me")
        assertTrue(runCatching { history.adopt(dir, SummaryVersion(notes = "")) }.isFailure)
        assertEquals("Keep me", File(dir, "notes.md").readText())
        val broken = temp.newFolder()
        File(broken, "notes.md").writeText("Keep me too")
        File(broken, "notes-history").writeText("blocks directory creation")
        assertTrue(runCatching { history.adopt(broken, SummaryVersion(notes = "New")) }.isFailure)
        assertEquals("Keep me too", File(broken, "notes.md").readText())
    }

    @Test
    fun `history is per meeting and tolerates partial files`() {
        val a = temp.newFolder()
        val b = temp.newFolder()
        history.adopt(a, SummaryVersion(notes = "A"))
        File(a, "notes-history/broken.json").writeText("{")
        File(a, "notes-history/incomplete.tmp").writeText("partial")
        assertEquals(1, history.list(a).size)
        assertTrue(history.list(b).isEmpty())
        assertFalse(File(b, "notes.md").exists())
    }
}
