package com.katavu.usage.upload

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

sealed class KatavuResult {
    data class Ok(val usage: KatavuUsage) : KatavuResult()
    data class Error(val message: String) : KatavuResult()
}

/** Fetches GET /api/katavu/usage with the hardcoded key. */
class KatavuUsageClient(private val client: OkHttpClient = HttpClients.shared) {

    fun fetch(): KatavuResult {
        val baseUrl = AppConfig.baseUrl
            ?: return KatavuResult.Error("App was built without a server URL")
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("api/katavu/usage").build())
            .header(RecordingUploader.API_KEY_HEADER, AppConfig.apiKey)
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> KatavuResult.Ok(KatavuUsage.fromJson(body))
                    response.code == 401 || response.code == 403 -> KatavuResult.Error("Server rejected the API key")
                    response.code == 503 -> KatavuResult.Error("Server can't reach the KATAVU database")
                    else -> KatavuResult.Error("Server error (HTTP ${response.code})")
                }
            }
        } catch (e: IOException) {
            KatavuResult.Error(e.message ?: "Could not reach the server")
        } catch (e: Exception) {
            KatavuResult.Error("Unexpected response from the server")
        }
    }
}
