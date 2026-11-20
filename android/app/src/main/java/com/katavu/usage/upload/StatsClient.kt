package com.katavu.usage.upload

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

sealed class StatsResult {
    data class Ok(val stats: UsageStats) : StatsResult()
    data class Error(val message: String) : StatsResult()
}

/** Fetches GET /api/stats with the hardcoded key. Pure JVM for testing. */
class StatsClient(private val client: OkHttpClient = HttpClients.shared) {

    fun fetch(): StatsResult {
        val baseUrl = AppConfig.baseUrl
            ?: return StatsResult.Error("App was built without a server URL")
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("api/stats").build())
            .header(RecordingUploader.API_KEY_HEADER, AppConfig.apiKey)
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> StatsResult.Ok(UsageStats.fromJson(body))
                    response.code == 401 || response.code == 403 -> StatsResult.Error("Server rejected the API key")
                    else -> StatsResult.Error("Server error (HTTP ${response.code})")
                }
            }
        } catch (e: IOException) {
            StatsResult.Error(e.message ?: "Could not reach the server")
        } catch (e: Exception) {
            StatsResult.Error("Unexpected response from the server")
        }
    }
}
