package com.example.callrecorder.upload

import android.content.Context
import android.media.MediaMetadataRetriever
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Where recordings live and how metadata is recovered from files we have no record of. */
object RecordingFiles {
    const val EXTENSION = "m4a"

    private val stampFormat = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    /** Naming used by CallRecorderService: 20261117_093000_out_+15551234567_CALL.m4a */
    private val namePattern =
        Regex("""^(\d{8}_\d{6})_(in|out)_([^_]*)_(CALL|VREC|MIC)\.m4a$""", RegexOption.IGNORE_CASE)

    fun directory(context: Context): File? = context.getExternalFilesDir("recordings")

    fun list(context: Context): List<File> =
        directory(context)?.listFiles { f -> f.isFile && f.extension.equals(EXTENSION, true) }?.toList()
            ?: emptyList()

    fun fileName(startedAtMillis: Long, direction: String, number: String?, source: String): String {
        val stamp = stampFormat.format(
            LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(startedAtMillis), ZoneId.systemDefault()))
        val safeNumber = number.orEmpty().filter { it.isDigit() || it == '+' }
        return "${stamp}_${direction}_${safeNumber}_$source.$EXTENSION"
    }

    /**
     * Best-effort metadata for a file found on disk without an entry (e.g. recorded before
     * uploads were added). Parses our own naming scheme; otherwise falls back to file times.
     */
    fun inferMetadata(file: File): RecordingMetadata {
        val duration = readDurationSeconds(file)
        namePattern.matchEntire(file.name)?.let { m ->
            val started = LocalDateTime.parse(m.groupValues[1], stampFormat)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            return RecordingMetadata(
                direction = m.groupValues[2].lowercase(),
                phoneNumber = m.groupValues[3].ifBlank { null },
                startedAtMillis = started,
                durationSeconds = duration,
                audioSource = m.groupValues[4].uppercase(),
            )
        }
        val lower = file.name.lowercase()
        val source = listOf(RecordingMetadata.SOURCE_CALL, RecordingMetadata.SOURCE_VREC, RecordingMetadata.SOURCE_MIC)
            .firstOrNull { file.name.contains(it, ignoreCase = true) } ?: RecordingMetadata.SOURCE_MIC
        return RecordingMetadata(
            direction = if ("out" in lower) RecordingMetadata.DIRECTION_OUT else RecordingMetadata.DIRECTION_IN,
            phoneNumber = Regex("""\+?\d{6,15}""").find(file.nameWithoutExtension.replace(Regex("""^\d{8}_\d{6}"""), ""))?.value,
            startedAtMillis = file.lastModified() - duration * 1000L,
            durationSeconds = duration,
            audioSource = source,
        )
    }

    fun readDurationSeconds(file: File): Int = try {
        MediaMetadataRetriever().run {
            try {
                setDataSource(file.absolutePath)
                ((extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000L).toInt()
            } finally {
                release()
            }
        }
    } catch (e: Exception) {
        0
    }
}
