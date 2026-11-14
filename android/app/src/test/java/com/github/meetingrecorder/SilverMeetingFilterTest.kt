package com.github.meetingrecorder

import com.github.meetingrecorder.data.Meeting
import com.github.meetingrecorder.ui.meetings.MeetingFilter
import com.github.meetingrecorder.ui.meetings.filterMeetings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

class SilverMeetingFilterTest {
    @Test fun `query combines with transcript and summary state without hiding unfiltered meetings`() {
        val a = Meeting(File("a"), "10:30", LocalDateTime.of(2026, 11, 14, 10, 30), "产品周会", true, true, true, 300)
        val b = a.copy(path = File("b"), title = "API Review", hasNotes = false, hasTranscript = false)
        val c = a.copy(path = File("c"), title = null, hasNotes = false)
        val all = listOf(a, b, c)
        assertEquals(all, filterMeetings(all, "", MeetingFilter.ALL))
        assertEquals(listOf(b), filterMeetings(all, " api ", MeetingFilter.UNTRANSCRIBED))
        assertEquals(listOf(a), filterMeetings(all, "周会", MeetingFilter.SUMMARIZED))
        assertEquals(emptyList<Meeting>(), filterMeetings(all, "api", MeetingFilter.SUMMARIZED))
        assertEquals(all, filterMeetings(all, "2026-11-14", MeetingFilter.ALL))
    }
}
