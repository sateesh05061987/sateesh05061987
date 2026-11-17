package com.example.callrecorder.upload

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClients {
    /**
     * TLS-only builder: no CLEARTEXT connection spec, and no following redirects that could
     * downgrade to http. The manifest's network security config blocks cleartext as well.
     */
    fun newBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.RESTRICTED_TLS))
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(2, TimeUnit.MINUTES)

    val shared: OkHttpClient by lazy { newBuilder().build() }
}
