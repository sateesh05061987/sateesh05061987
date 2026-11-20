package com.katavu.usage.upload

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Upload settings kept in SharedPreferences. */
class UploadSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_URL, "") ?: ""
        set(value) = prefs.edit { putString(KEY_URL, value.trim()) }

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit { putString(KEY_API_KEY, value.trim()) }

    var deleteAfterUpload: Boolean
        get() = prefs.getBoolean(KEY_DELETE, false)
        set(value) = prefs.edit { putBoolean(KEY_DELETE, value) }

    /** The parsed base URL, or null when it is missing or not https. */
    val baseUrl: HttpUrl? get() = parseServerUrl(serverUrl)

    val isConfigured: Boolean get() = baseUrl != null && apiKey.isNotBlank()

    companion object {
        private const val PREFS = "upload_settings"
        private const val KEY_URL = "server_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_DELETE = "delete_after_upload"

        /** Accepts only absolute https URLs; strips a trailing slash, query and fragment. */
        fun parseServerUrl(raw: String): HttpUrl? {
            val url = raw.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
            if (!url.isHttps) return null
            return url.newBuilder().query(null).fragment(null).build()
        }
    }
}
