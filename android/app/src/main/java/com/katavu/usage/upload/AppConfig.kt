package com.katavu.usage.upload

import com.katavu.usage.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Server URL and API key, baked into the APK at build time from apikey.properties
 * (see app/build.gradle.kts). No in-app settings screen: these are fixed per build.
 */
object AppConfig {
    /** https base URL, or null if the build was made without a valid one. */
    val baseUrl: HttpUrl? = parseServerUrl(BuildConfig.SERVER_URL)

    val apiKey: String = BuildConfig.API_KEY

    val isConfigured: Boolean get() = baseUrl != null && apiKey.isNotBlank()

    /** Delete the phone's local copy once a recording has been uploaded to the server. */
    const val DELETE_AFTER_UPLOAD = true

    /** Accepts only absolute https URLs; strips a trailing slash, query and fragment. */
    fun parseServerUrl(raw: String?): HttpUrl? {
        val url = (raw ?: "").trim().trimEnd('/').toHttpUrlOrNull() ?: return null
        if (!url.isHttps) return null
        return url.newBuilder().query(null).fragment(null).build()
    }
}
