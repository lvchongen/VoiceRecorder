package com.github.meetingrecorder

import com.github.meetingrecorder.data.chronologicalTranscript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTimelineTest {
    @Test fun `time order preserves utterances and multiline continuations`() {
        val source = "开场说明\n[00:00:20] **B:** 第二段\n补充说明\n\n[00:00:05] **A:** 第一段"
        val displayed = chronologicalTranscript(source)
        assertTrue(displayed.startsWith("开场说明"))
        assertTrue(displayed.indexOf("00:00:05") < displayed.indexOf("00:00:20"))
        assertTrue(displayed.contains("**B:** 第二段\n补充说明"))
        assertFalse(displayed.contains("[P"))
    }

    @Test fun `equal timestamps retain source order and long hours remain valid`() {
        val displayed = chronologicalTranscript("[100:00:00] 后段\n[00:00:01] A\n[00:00:01] B")
        assertTrue(displayed.indexOf("A") < displayed.indexOf("B"))
        assertTrue(displayed.indexOf("B") < displayed.indexOf("后段"))
    }

    @Test fun `missing timestamps are kept without fabricated times`() {
        assertEquals("A: 没有时间戳", chronologicalTranscript("A: 没有时间戳"))
        assertEquals("[00:99:00] 非法时间", chronologicalTranscript("[00:99:00] 非法时间"))
    }
}
