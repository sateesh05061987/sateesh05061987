package com.katavu.usage.upload

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneId

class RecordingUploaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var uploader: RecordingUploader

    private val meta = RecordingMetadata(
        direction = "out",
        phoneNumber = "+15551234567",
        startedAtMillis = 1_763_371_800_000L, // 2025-11-17T09:30:00Z
        durationSeconds = 42,
        audioSource = "VREC",
    )

    @Before
    fun setUp() {
        val localhost = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(localhost).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(localhost.certificate).build()
        server = MockWebServer().apply {
            useHttps(serverCerts.sslSocketFactory(), false)
            start()
        }
        uploader = RecordingUploader(
            HttpClients.newBuilder()
                .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
                .build()
        )
    }

    @After
    fun tearDown() = server.shutdown()

    // Force host=localhost (the cert's SAN) whatever MockWebServer bound to (127.0.0.1 on CI,
    // kubernetes.docker.internal on some dev machines), so TLS hostname verification passes.
    private fun baseUrl() = AppConfig.parseServerUrl(
        server.url("/").newBuilder().host("localhost").build().toString())!!

    @Test
    fun `sends multipart form with api key and all fields`() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id": 17, "fileName":"a.m4a"}"""))
        val file = tmp.newFile("20251117_093000_out_+15551234567_VREC.m4a").apply { writeBytes(ByteArray(64) { 1 }) }

        val outcome = uploader.upload(baseUrl(), "secret-key", file, meta, "Pixel 8", ZoneId.of("Asia/Kolkata"))

        assertEquals(UploadOutcome.Success(17, duplicate = false), outcome)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/recordings", req.path)
        assertEquals("secret-key", req.getHeader("X-Api-Key"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = req.body.readUtf8()
        fun field(name: String, value: String) =
            assertTrue("missing $name", body.contains("name=\"$name\"\r\nContent-Length: ${value.length}\r\n\r\n$value\r\n"))
        field("direction", "out")
        field("phoneNumber", "+15551234567")
        field("startedAt", "2025-11-17T15:00:00+05:30")
        field("durationSeconds", "42")
        field("audioSource", "VREC")
        field("deviceName", "Pixel 8")
        assertTrue(body.contains("name=\"file\"; filename=\"${file.name}\""))
        assertTrue(body.contains("Content-Type: audio/mp4"))
    }

    @Test
    fun `duplicate counts as success`() {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"title":"dup","existingId":5}"""))
        val file = tmp.newFile("a.m4a").apply { writeBytes(ByteArray(8)) }
        assertEquals(UploadOutcome.Success(5, duplicate = true), uploader.upload(baseUrl(), "k", file, meta, "d"))
    }

    @Test
    fun `server errors retry, client errors fail`() {
        val file = tmp.newFile("a.m4a").apply { writeBytes(ByteArray(8)) }
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(uploader.upload(baseUrl(), "k", file, meta, "d") is UploadOutcome.Retry)
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(uploader.upload(baseUrl(), "k", file, meta, "d") is UploadOutcome.Failure)
        server.enqueue(MockResponse().setResponseCode(400).setBody("bad"))
        assertTrue(uploader.upload(baseUrl(), "k", file, meta, "d") is UploadOutcome.Failure)
    }

    @Test
    fun `network failure retries`() {
        val file = tmp.newFile("a.m4a").apply { writeBytes(ByteArray(8)) }
        val url = baseUrl()
        server.shutdown()
        assertTrue(uploader.upload(url, "k", file, meta, "d") is UploadOutcome.Retry)
    }

    @Test
    fun `ping reports success and bad key`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        assertNull(uploader.ping(baseUrl(), "k"))
        assertEquals("/api/ping", server.takeRequest().path)
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(uploader.ping(baseUrl(), "k")!!.contains("API key"))
    }

    @Test
    fun `http urls are refused`() {
        assertNull(AppConfig.parseServerUrl("http://example.com"))
        assertNull(AppConfig.parseServerUrl("example.com"))
        assertEquals("https://example.com/sub", AppConfig.parseServerUrl("https://example.com/sub/")!!.toString())
        val file = tmp.newFile("a.m4a")
        assertTrue(uploader.upload("http://example.com/".toHttpUrl(), "k", file, meta, "d") is UploadOutcome.Failure)
    }
}
