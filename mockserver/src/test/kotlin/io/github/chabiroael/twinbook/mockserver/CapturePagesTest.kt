package io.github.chabiroael.twinbook.mockserver

import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CapturePagesTest {
    private lateinit var server: MockServer

    @Before
    fun setUp() {
        server = MockServer().start()
    }

    @After
    fun tearDown() = server.close()

    private fun get(path: String): HttpURLConnection = URI(server.url(path)).toURL().openConnection() as HttpURLConnection

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun secretsPagePlantsTokensUnkeyedAndSetsCookiesWithAttributes() {
        val s = MockSecrets("r1")
        assertEquals(s.dtsg, MockSecrets("r1").dtsg)
        assertFalse(s.dtsg == MockSecrets("r2").dtsg)
        assertEquals(15, s.userId.length)
        assertEquals(5, s.jazoest.length)
        val c = get("/secrets/page?run=r1")
        val html = c.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(html.contains("window.__boot = [\"${s.dtsg}\", ${s.userId}]"))
        assertFalse(html.contains("\"token\""))
        val cookies = c.headerFields["Set-Cookie"].orEmpty()
        assertTrue(cookies.any { it == "mock_sess=${s.session}; Path=/; HttpOnly; SameSite=Lax; Max-Age=3600" })
        assertTrue(cookies.any { it.startsWith("c_user=${s.userId};") && it.contains("Secure") })
    }

    @Test
    fun bloksEndpointServesOneGuardedDocument() {
        val c = get("/async/wbloks/fetch/?run=r1")
        assertEquals(CapturePages.JS_TYPE, c.contentType)
        val body = c.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(body.startsWith("for (;;);{\"__ar\":1,"))
        assertFalse(body.contains('\n'))
    }

    @Test
    fun bulkBodiesHaveTheExactSizeAndTheServerRecordsTheirHash() {
        for (size in listOf(20, 1000, 5_500_000)) assertEquals(size, CapturePages.bulkBody(3, size).size)
        val body = get("/bulk?i=7&size=4096").inputStream.readBytes()
        assertEquals(sha(CapturePages.bulkBody(7, 4096)), sha(body))
        val recorded = server.requestsTo("/bulk").single()
        assertEquals(200, recorded.responseStatus)
        assertEquals(sha(body), recorded.responseSha256)
        assertEquals(4096L, recorded.responseLength)
    }

    @Test
    fun logEndpointKeepsValuesPerRun() {
        val c = get("/log?run=r9&field=email")
        c.requestMethod = "POST"
        c.doOutput = true
        c.outputStream.use { it.write("abc".toByteArray()) }
        assertEquals(204, c.responseCode)
        assertEquals("abc", server.lastLog("r9", "email"))
    }
}
