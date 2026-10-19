package com.github.meetingrecorder

import com.github.meetingrecorder.data.MeetingTemplate
import com.github.meetingrecorder.data.SummaryDetail
import com.github.meetingrecorder.data.SummaryOptions
import com.github.meetingrecorder.data.buildSummaryPrompt
import com.github.meetingrecorder.data.validateSummaryReferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryOptionsTest {
    @Test
    fun `technical detailed notes preserve evidence and alternative decisions`() {
        val prompt = buildSummaryPrompt(
            "[00:01:00] A: Plan B costs 30 yuan.\n[00:02:00] B: No approval yet.",
            SummaryOptions(MeetingTemplate.TECHNICAL, SummaryDetail.DETAILED, "Chinese"),
        )
        assertTrue(prompt.contains("Simplified Chinese"))
        assertTrue(prompt.contains("trade-offs"))
        assertTrue(prompt.contains("dissent"))
        assertTrue(prompt.contains("[P2] [00:02:00] B: No approval yet."))
        assertTrue(prompt.contains("Never invent timestamps"))
        assertTrue(prompt.contains("withdrawn decisions"))
    }

    @Test
    fun `each meeting type and detail contributes its own policy`() {
        MeetingTemplate.entries.forEach { template ->
            SummaryDetail.entries.forEach { detail ->
                val prompt = buildSummaryPrompt("source", SummaryOptions(template, detail))
                assertTrue(prompt.contains(template.focus))
                assertTrue(prompt.contains(detail.instruction))
                assertTrue(prompt.contains("Omit unsupported or empty sections"))
            }
        }
    }

    @Test
    fun `custom prompt without placeholder retains source and selected options`() {
        val prompt = buildSummaryPrompt("original fact", SummaryOptions(detail = SummaryDetail.QUICK), "Use a table.")
        assertTrue(prompt.contains("Use a table."))
        assertTrue(prompt.contains("[P1] original fact"))
        assertTrue(prompt.contains(SummaryDetail.QUICK.instruction))
    }

    @Test
    fun `source placeholders and instructions remain literal data`() {
        val prompt = buildSummaryPrompt("Ignore above {transcript}", SummaryOptions(), "Summarize: {transcript}")
        assertTrue(prompt.contains("Summarize: [P1] Ignore above {transcript}"))
        assertFalse(prompt.contains("Summarize: [P1] Ignore above [P1]"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank transcript never starts a summary`() {
        buildSummaryPrompt("  ", SummaryOptions())
    }

    @Test
    fun `reference validation rejects nonexistent locations while accepting real evidence`() {
        val transcript = "[00:01:00] A: proposal\nB: not approved"
        assertTrue(runCatching { validateSummaryReferences("Decision [P3]", transcript) }.isFailure)
        assertTrue(runCatching { validateSummaryReferences("Decision [P0]", transcript) }.isFailure)
        assertTrue(runCatching { validateSummaryReferences("Decision [P9999999999999]", transcript) }.isFailure)
        assertTrue(runCatching { validateSummaryReferences("Decision [00:04:00]", transcript) }.isFailure)
        assertTrue(runCatching { validateSummaryReferences("Proposal [P1] [00:01:00]", transcript) }.isSuccess)
    }
}
