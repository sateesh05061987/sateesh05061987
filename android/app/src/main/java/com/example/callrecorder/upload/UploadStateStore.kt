package com.example.callrecorder.upload

import android.content.Context
import android.util.Log
import androidx.core.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class UploadStatus { PENDING, UPLOADING, UPLOADED, FAILED }

data class UploadEntry(
    val fileName: String,
    val meta: RecordingMetadata,
    val status: UploadStatus = UploadStatus.PENDING,
    val attempts: Int = 0,
    val lastError: String? = null,
    val uploadedAtMillis: Long? = null,
    val remoteId: Long? = null,
    val localDeleted: Boolean = false,
)

/**
 * Upload bookkeeping, one entry per recording file, persisted as a small JSON file
 * (filesDir/upload_state.json). Process-wide singleton; all access is synchronized.
 */
class UploadStateStore private constructor(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "upload_state.json"))
    private val lock = Any()
    private val _entries = MutableStateFlow(load())

    /** All entries keyed by file name. Collect this to keep the UI up to date. */
    val entries: StateFlow<Map<String, UploadEntry>> = _entries.asStateFlow()

    fun get(fileName: String): UploadEntry? = _entries.value[fileName]

    fun put(entry: UploadEntry) = mutate { it + (entry.fileName to entry) }

    /** Applies [transform] to an existing entry. Does nothing if the entry is missing. */
    fun update(fileName: String, transform: (UploadEntry) -> UploadEntry) = mutate { map ->
        val current = map[fileName] ?: return@mutate map
        map + (fileName to transform(current))
    }

    private fun mutate(change: (Map<String, UploadEntry>) -> Map<String, UploadEntry>) {
        synchronized(lock) {
            val next = change(_entries.value)
            if (next === _entries.value) return
            save(next)
            _entries.value = next
        }
    }

    private fun load(): Map<String, UploadEntry> = try {
        if (!file.baseFile.exists()) emptyMap() else {
            val array = JSONArray(String(file.readFully(), Charsets.UTF_8))
            (0 until array.length()).map { fromJson(array.getJSONObject(it)) }.associateBy { it.fileName }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Could not read upload state; starting fresh", e)
        emptyMap()
    }

    private fun save(map: Map<String, UploadEntry>) {
        val array = JSONArray()
        map.values.forEach { array.put(toJson(it)) }
        val out = file.startWrite()
        try {
            out.write(array.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            Log.e(TAG, "Could not save upload state", e)
        }
    }

    private fun toJson(e: UploadEntry) = JSONObject().apply {
        put("fileName", e.fileName)
        put("direction", e.meta.direction)
        put("phoneNumber", e.meta.phoneNumber ?: JSONObject.NULL)
        put("startedAt", e.meta.startedAtMillis)
        put("durationSeconds", e.meta.durationSeconds)
        put("audioSource", e.meta.audioSource)
        put("status", e.status.name)
        put("attempts", e.attempts)
        put("lastError", e.lastError ?: JSONObject.NULL)
        put("uploadedAt", e.uploadedAtMillis ?: JSONObject.NULL)
        put("remoteId", e.remoteId ?: JSONObject.NULL)
        put("localDeleted", e.localDeleted)
    }

    private fun fromJson(o: JSONObject) = UploadEntry(
        fileName = o.getString("fileName"),
        meta = RecordingMetadata(
            direction = o.getString("direction"),
            phoneNumber = o.optStringOrNull("phoneNumber"),
            startedAtMillis = o.getLong("startedAt"),
            durationSeconds = o.getInt("durationSeconds"),
            audioSource = o.getString("audioSource"),
        ),
        status = runCatching { UploadStatus.valueOf(o.getString("status")) }.getOrDefault(UploadStatus.PENDING),
        attempts = o.optInt("attempts"),
        lastError = o.optStringOrNull("lastError"),
        uploadedAtMillis = if (o.isNull("uploadedAt")) null else o.getLong("uploadedAt"),
        remoteId = if (o.isNull("remoteId")) null else o.getLong("remoteId"),
        localDeleted = o.optBoolean("localDeleted"),
    )

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)

    companion object {
        private const val TAG = "UploadStateStore"

        @Volatile private var instance: UploadStateStore? = null

        fun get(context: Context): UploadStateStore =
            instance ?: synchronized(this) {
                instance ?: UploadStateStore(context.applicationContext).also { instance = it }
            }
    }
}
