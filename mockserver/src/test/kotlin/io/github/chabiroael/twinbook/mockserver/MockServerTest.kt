package io.github.chabiroael.twinbook.mockserver

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.util.zip.GZIPInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MockServerTest {
    private lateinit var server: MockServer

    @Before
    fun setUp() {
        server = MockServer().start()
    }

    @After
    fun tearDown() = server.close()

    private fun open(path: String, method: String = "GET", body: String? = null, headers: Map<String, String> = emptyMap()): HttpURLConnection {
        val c = URI(server.url(path)).toURL().openConnection() as HttpURLConnection
        c.requestMethod = method
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        return c
    }

    @Test
    fun bindsToLoopbackOnly() {
        assertTrue(server.origin.startsWith("http://127.0.0.1:"))
    }

    @Test
    fun servesStaticPages() {
        val c = open("/anchor")
        assertEquals(200, c.responseCode)
        val html = c.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(html.contains("anchor"))
        assertTrue(!html.contains("<script"))
        assertEquals(200, open("/test.html").responseCode)
        assertTrue(open("/static/test-page.js").inputStream.readBytes().toString(Charsets.UTF_8).contains("integrity"))
        assertEquals(404, open("/nope").responseCode)
    }

    @Test
    fun streamsAScenarioWithChunkTimes() {
        val c = open("/api/graphql/?scenario=slow&run=r1&transport=test", "POST", "doc_id=1&fb_dtsg=x")
        val bytes = c.inputStream.readBytes()
        assertEquals(Scenarios.get("slow").bodyText, bytes.toString(Charsets.UTF_8))
        val trace = server.streamTraces.single()
        assertEquals("r1", trace.run)
        assertEquals(Scenarios.get("slow").chunks().size, trace.chunkSentAtMillis.size)
        assertTrue(trace.chunkSentAtMillis.last() - trace.chunkSentAtMillis.first() >= 400L * (trace.chunkSentAtMillis.size - 1) - 50)
        val recorded = server.requestsTo("/api/graphql/").single()
        assertEquals(listOf("doc_id", "fb_dtsg"), recorded.formFieldNames)
        assertEquals("x", recorded.form["fb_dtsg"])
    }

    @Test
    fun usesChunkedFramingOnTheWire() {
        Socket("127.0.0.1", server.port).use { s ->
            s.getOutputStream().write("POST /api/graphql/?scenario=ads-first HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n".toByteArray())
            val raw = BufferedInputStream(s.getInputStream()).readBytes().toString(Charsets.ISO_8859_1)
            assertTrue(raw.contains("Transfer-Encoding: chunked"))
            assertTrue(raw.endsWith("0\r\n\r\n"))
            val chunkCount = Regex("\r\n[0-9a-f]+\r\n").findAll(raw).count()
            assertTrue(chunkCount >= Scenarios.get("ads-first").chunks().size)
        }
    }

    @Test
    fun gzipScenarioIsCompressed() {
        val c = open("/api/graphql/?scenario=gzip", "POST", "")
        assertEquals("gzip", c.getHeaderField("Content-Encoding"))
        val decoded = GZIPInputStream(c.inputStream).readBytes().toString(Charsets.UTF_8)
        assertEquals(Scenarios.get("gzip").bodyText, decoded)
    }

    @Test
    fun setsEveryCookieKindAndEchoesCookies() {
        val c = open("/session/set-cookies?prefix=t&value=v1&maxAge=600")
        assertEquals(200, c.responseCode)
        val cookies = c.headerFields["Set-Cookie"].orEmpty()
        assertEquals(CookieKind.entries.size, cookies.size)
        assertTrue(cookies.any { it.startsWith("t_httponly=v1") && it.contains("HttpOnly") && it.contains("Max-Age=600") })
        assertTrue(cookies.any { it.startsWith("t_none=v1") && it.contains("SameSite=None; Secure") })

        val echo = open("/session/echo?pad=2", "POST", "a=1", mapOf("Cookie" to "t_plain=v1; t_lax=v1", "X-Probe" to "http://x"))
        val json = echo.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(json, json.contains("\"cookies\":{\"t_plain\":\"v1\",\"t_lax\":\"v1\"}"))
        assertTrue(json.contains("[\"X-Probe\",\"http://x\"]"))
        assertTrue(json.contains(MockServer.payload(2)))
    }

    @Test
    fun collectsReports() {
        val t = Thread {
            Thread.sleep(100)
            open("/report?run=abc", "POST", "{\"ok\":true}").responseCode
        }
        t.start()
        assertEquals("{\"ok\":true}", server.awaitReport("abc", 5_000))
        t.join()
    }

    @Test
    fun servesLargeScenarioCompletely() {
        val c = open("/api/graphql/?scenario=large", "POST", "")
        val out = ByteArrayOutputStream()
        c.inputStream.copyTo(out)
        assertEquals(Scenarios.get("large").body.size, out.size())
    }
}
