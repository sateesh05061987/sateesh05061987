package com.example.callrecorder.upload

import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

sealed class UploadOutcome {
    /** Stored on the server. [duplicate] is true when the server already had this exact file. */
    data class Success(val remoteId: Long?, val duplicate: Boolean) : UploadOutcome()

    /** Transient problem (network, 5xx, 429): try again later. */
    data class Retry(val message: String) : UploadOutcome()

    /** The server rejected the upload; retrying the same request will not help. */
    data class Failure(val message: String) : UploadOutcome()
}

/** Talks to the recording server. Pure JVM so it can be unit tested with MockWebServer. */
class RecordingUploader(private val client: OkHttpClient = HttpClients.shared) {

    fun upload(
        baseUrl: HttpUrl,
        apiKey: String,
        file: File,
        meta: RecordingMetadata,
        deviceName: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): UploadOutcome {
        if (!baseUrl.isHttps) return UploadOutcome.Failure("Server URL must use https")

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody(AUDIO_MP4))
            .addFormDataPart("direction", meta.direction)
            .addFormDataPart("phoneNumber", meta.phoneNumber.orEmpty())
            .addFormDataPart("startedAt", isoTimestamp(meta.startedAtMillis, zone))
            .addFormDataPart("durationSeconds", meta.durationSeconds.toString())
            .addFormDataPart("audioSource", meta.audioSource)
            .addFormDataPart("deviceName", deviceName)
            .build()

        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("api/recordings").build())
            .header(API_KEY_HEADER, apiKey)
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                when (response.code) {
                    200, 201 -> UploadOutcome.Success(extractId(text, "id"), duplicate = false)
                    409 -> UploadOutcome.Success(extractId(text, "existingId"), duplicate = true)
                    408, 429, in 500..599 -> UploadOutcome.Retry("HTTP ${response.code}")
                    401, 403 -> UploadOutcome.Failure("API key rejected (HTTP ${response.code})")
                    413 -> UploadOutcome.Failure("File too large for the server (HTTP 413)")
                    else -> UploadOutcome.Failure("HTTP ${response.code}: ${text.take(300)}")
                }
            }
        } catch (e: IOException) {
            UploadOutcome.Retry(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Calls GET /api/ping. Returns null on success, otherwise a human-readable problem. */
    fun ping(baseUrl: HttpUrl, apiKey: String): String? {
        if (!baseUrl.isHttps) return "Server URL must use https"
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("api/ping").build())
            .header(API_KEY_HEADER, apiKey)
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> null
                    401, 403 -> "Server reached, but the API key was rejected (HTTP ${response.code})."
                    404 -> "Server reached, but /api/ping was not found. Check the URL."
                    else -> "Server answered HTTP ${response.code}."
                }
            }
        } catch (e: IOException) {
            "Could not connect: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    companion object {
        const val API_KEY_HEADER = "X-Api-Key"
        private val AUDIO_MP4 = "audio/mp4".toMediaType()

        /** ISO 8601 with the phone's UTC offset, e.g. 2026-11-17T09:30:00+05:30. */
        fun isoTimestamp(millis: Long, zone: ZoneId): String =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(millis).atZone(zone))

        private fun extractId(json: String, field: String): Long? =
            Regex("\"$field\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull()
    }
}
