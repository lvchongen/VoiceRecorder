package com.github.meetingrecorder.audio

import java.io.File

/** Prefer only a successfully finalized denoised file, with original/legacy fallback. */
fun preferredRecording(dir: File): File? = sequenceOf("recording-denoised.m4a", "recording.m4a", "recording.mp3")
    .map { File(dir, it) }.firstOrNull { it.isFile && it.length() > 0L }
