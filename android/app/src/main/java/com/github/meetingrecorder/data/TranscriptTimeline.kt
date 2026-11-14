package com.github.meetingrecorder.data

/** Display source utterances in time order; keep untimed text and never invent timestamps. */
fun chronologicalTranscript(transcript: String): String {
    val pattern = Regex("(?m)^\\s*\\[(\\d{2,}):([0-5]\\d):([0-5]\\d)\\]\\s*")
    val matches = pattern.findAll(transcript).filter { it.groupValues[1].toLongOrNull()?.let { hour -> hour <= Long.MAX_VALUE / 3600 - 1 } == true }.toList()
    if (matches.isEmpty()) return transcript
    val prefix = transcript.substring(0, matches.first().range.first).trim()
    val entries = matches.mapIndexed { index, match ->
        val seconds = match.groupValues[1].toLong() * 3600 + match.groupValues[2].toLong() * 60 + match.groupValues[3].toLong()
        val body = transcript.substring(match.range.last + 1, matches.getOrNull(index + 1)?.range?.first ?: transcript.length).trim()
        seconds to "**[${match.groupValues[1]}:${match.groupValues[2]}:${match.groupValues[3]}]**  \n$body"
    }.sortedBy { it.first }.map { it.second }
    return (listOf(prefix).filter { it.isNotBlank() } + entries).joinToString("\n\n")
}

/** Hide generated reference markers in normal reading; stored source and history remain intact. */
fun withoutParagraphReferences(text: String): String = text
    .replace("重叠/未确认", "未确认")
    .replace(Regex("\\[P\\d+(?:\\s*[,，、–—-]\\s*P?\\d+)*\\]"), "")
    .replace(Regex("(?m)^[ \t]+$"), "")
