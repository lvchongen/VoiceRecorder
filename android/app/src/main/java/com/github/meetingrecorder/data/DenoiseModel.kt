package com.github.meetingrecorder.data

/** Both models use the same 16 kHz capture path, preserving raw audio alongside enhancement. */
enum class DenoiseModel(val label: String) {
    DPDFNET8("DPDFNet8（人声保留，默认）"),
    GTCRN("GTCRN（轻量）"),
}
