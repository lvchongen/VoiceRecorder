package com.github.meetingrecorder

import com.github.meetingrecorder.audio.RecordingPhase
import com.github.meetingrecorder.ui.main.StopOutcome
import com.github.meetingrecorder.ui.main.decideStopOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingStopDecisionTest {

    private fun decide(
        phase: RecordingPhase = RecordingPhase.STOPPED,
        fileExists: Boolean = true,
        fileLength: Long = 1_024,
        silenced: Boolean = false,
    ) = decideStopOutcome(phase, fileExists, fileLength, silenced)

    @Test
    fun `failed phase reports file not found`() {
        assertEquals(StopOutcome.FILE_NOT_FOUND, decide(phase = RecordingPhase.FAILED))
    }

    @Test
    fun `missing file reports file not found`() {
        assertEquals(StopOutcome.FILE_NOT_FOUND, decide(fileExists = false))
    }

    @Test
    fun `zero-byte file reports empty`() {
        assertEquals(StopOutcome.EMPTY, decide(fileLength = 0L))
    }

    @Test
    fun `silenced non-empty recording is surfaced as a warning, not transcribed`() {
        assertEquals(StopOutcome.SILENCED, decide(silenced = true))
    }

    @Test
    fun `empty file takes precedence over silenced`() {
        // A 0-byte file gets the more actionable storage-permission message even if also silenced.
        assertEquals(StopOutcome.EMPTY, decide(fileLength = 0L, silenced = true))
    }

    @Test
    fun `normal recording saves audio without starting processing`() {
        assertEquals(StopOutcome.SAVE_AUDIO, decide())
    }
}
