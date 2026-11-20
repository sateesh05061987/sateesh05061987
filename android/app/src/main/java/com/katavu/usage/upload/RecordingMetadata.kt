package com.katavu.usage.upload

/** What the server needs to know about a recording besides the audio itself. */
data class RecordingMetadata(
    /** "in" or "out". */
    val direction: String,
    val phoneNumber: String?,
    val startedAtMillis: Long,
    val durationSeconds: Int,
    /** "CALL", "VREC" or "MIC". */
    val audioSource: String,
) {
    companion object {
        const val DIRECTION_IN = "in"
        const val DIRECTION_OUT = "out"
        const val SOURCE_CALL = "CALL"
        const val SOURCE_VREC = "VREC"
        const val SOURCE_MIC = "MIC"
    }
}
