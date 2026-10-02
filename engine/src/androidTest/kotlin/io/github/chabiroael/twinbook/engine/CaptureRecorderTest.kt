package io.github.chabiroael.twinbook.engine

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.chabiroael.twinbook.capture.CaptureStore
import io.github.chabiroael.twinbook.capture.TaintScrubber
import io.github.chabiroael.twinbook.engine.TestEngine.evidence
import io.github.chabiroael.twinbook.engine.TestEngine.server
import io.github.chabiroael.twinbook.mockserver.CapturePages
import io.github.chabiroael.twinbook.mockserver.MockSecrets
import io.github.chabiroael.twinbook.mockserver.RecordedRequest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * C4 recorder completeness, C5 layer 1 at the source, C6 layer 2 at finalize, C8 lossless
 * transport under load, all against the mock with the mock profile captured.
 */
@RunWith(AndroidJUnit4::class)
class CaptureRecorderTest {
    companion object {
        private lateinit var session: EngineSession

        @BeforeClass
        @JvmStatic
        fun setUp() {
            runBlocking { TestEngine.ready() }
            session = TestEngine.newSession(UserAgentProfile.MOBILE, "capture")
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            TestEngine.onMain { session.close() }
        }
    }

    /** A failed test must not leave its capture running for the next one. */
    @org.junit.After
    fun discardLeftover() = runBlocking {
        if (recorder.isRecording) recorder.discard()
        Unit
    }

    private val recorder get() = TestEngine.recorder
    private val store get() = recorder.store

    private suspend fun loadAndReport(pathAndQuery: String, run: String): JSONObject {
        withContext(Dispatchers.Main) { session.load(server.url(pathAndQuery)) }
        return JSONObject(withContext(Dispatchers.IO) { server.awaitReport(run, 90_000) })
    }

    private fun lines(dir: File): List<JSONObject> = File(dir, CaptureStore.EVENTS).readLines().filter { it.isNotBlank() }.map { JSONObject(it) }

    /** True if [captured] equals [original] except inside layer 1 placeholders. */
    private fun sameModuloRedaction(original: String, captured: String): Boolean {
        if (original.length != captured.length) return false
        val spans = Regex("""!R\**!""").findAll(captured).map { it.range }.toList()
        return original.indices.all { i -> original[i] == captured[i] || spans.any { i in it } }
    }

    @Test
    fun recordsEveryRequestRedactsAtTheSourceAndScrubsAtFinalize() = runBlocking {
        val id = "c4-${System.currentTimeMillis()}"
        val firstRequest = server.requests.size
        recorder.start(listOf("mock"), id)
        val dir = store.dir(id)

        val feedRun = TestEngine.newRun("c4-feed")
        loadAndReport("/test.html?run=$feedRun&scenario=ads-first&transports=xhr,fetch", feedRun)
        val docRun = TestEngine.newRun("c4-doc")
        loadAndReport("/document?run=$docRun", docRun)
        val secretRun = TestEngine.newRun("c4-secrets")
        val secretsReport = loadAndReport("/secrets/page?run=$secretRun", secretRun)
        val secrets = MockSecrets(secretRun)
        assertEquals(secrets.session, secretsReport.getString("echo"))
        assertEquals("for (;;);", secretsReport.getString("bloksStart"))
        // Let the last records (report request, bodies) arrive.
        delay(2_000)

        // ---- C5: what left the extension (before finalize, layer 1 only)
        val raw = lines(dir)
        val headerLines = raw.filter { it.getString("ev") in setOf("sendHeaders", "headers", "redirect") }
        val headerText = headerLines.joinToString("\n") { it.toString() }
        for (v in listOf(secrets.session, secrets.userId)) assertFalse("cookie value in header records", headerText.contains(v))
        val cookieHeaders = headerLines.flatMap { l -> (0 until (l.optJSONArray("headers")?.length() ?: 0)).map { l.getJSONArray("headers").getJSONObject(it) } }
            .filter { it.getString("name").equals("cookie", true) || it.getString("name").equals("set-cookie", true) }
            // Gecko joins several Set-Cookie headers into one value, separated by newlines.
            .flatMap { h -> h.getString("value").split("\n").map { "${h.getString("name")}: $it" } }
        assertTrue(cookieHeaders.any { it.startsWith("Set-Cookie: mock_sess=!R") && it.endsWith("; Path=/; HttpOnly; SameSite=Lax; Max-Age=3600") })
        assertTrue(cookieHeaders.any { it.contains("c_user=!R") && it.contains("mock_sess=!R") })
        val form = raw.single { it.getString("ev") == "request" && it.getJSONObject("d").getString("url").contains("/secrets/form") }
        val fields = form.getJSONObject("body").getJSONArray("fields")
        val fieldMap = (0 until fields.length()).associate { fields.getJSONArray(it).getString(0) to fields.getJSONArray(it).getString(1) }
        assertEquals("!R" + "*".repeat(secrets.dtsg.length - 3) + "!", fieldMap["fb_dtsg"])
        assertEquals(secrets.lsd.length, fieldMap.getValue("lsd").length)
        assertTrue(fieldMap.getValue("lsd").startsWith("!R"))
        assertEquals("!R**!", fieldMap["jazoest"])
        assertEquals("1007000000", fieldMap["__rev"])
        assertEquals("1", fieldMap["__req"])
        val recordsText = raw.joinToString("\n") { it.toString() }
        for (v in listOf(secrets.dtsg, secrets.lsd, secrets.jazoest)) assertFalse("token value in records", recordsText.contains(v))
        val jsonUrl = raw.first { it.getString("ev") == "request" && it.getJSONObject("d").getString("url").contains("/secrets/json") }.getJSONObject("d").getString("url")
        assertTrue(jsonUrl.contains("fb_dtsg_ag=!R"))
        // Not keyed in the HTML, so still present before finalize: layer 2's job.
        val htmlBefore = raw.filter { it.getString("ev") == "body" }.map { File(dir, it.getString("file")).readText() }
        assertTrue(htmlBefore.any { it.contains(secrets.dtsg) })
        evidence("C5 before finalize: ${headerLines.size} header records, ${cookieHeaders.size} cookie headers, e.g. ${cookieHeaders.filter { "mock_sess" in it }.take(2)}; form fields $fieldMap; json url $jsonUrl; no cookie or keyed token value in any record")

        // ---- stop and finalize
        val t0 = SystemClock.elapsedRealtime()
        val last = recorder.stop()
        assertTrue(last.message, last.finalized)
        evidence("C6 finalize took ${SystemClock.elapsedRealtime() - t0} ms: $last")
        val serverRequests = server.requests.drop(firstRequest)

        // ---- C6: no planted secret anywhere in the finalized session
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        var hits = 0
        for (f in files) {
            val text = f.readBytes().toString(Charsets.ISO_8859_1)
            for ((label, v) in secrets.all) {
                for (variant in TaintScrubber.variants(v)) {
                    if (variant.length >= 8 && text.contains(String(variant.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1))) {
                        hits++
                        evidence("C6 FOUND $label in ${f.name}")
                    }
                }
            }
        }
        assertEquals(0, hits)
        val secretsHtml = files.map { it.readText() }.single { it.contains("mock secrets page") }
        assertTrue(secretsHtml.contains("window.__boot = [\"!T:field:fb_dtsg!\", !T:cookie:c_user!]"))
        val sessionJson = JSONObject(File(dir, "session.json").readText())
        evidence("C6 scan of ${files.size} finalized files for ${secrets.all.size} planted secrets in all encodings: $hits hits; secrets page now: ${Regex("""window.__boot = [^;]*;""").find(secretsHtml)?.value}; taint ${sessionJson.getJSONObject("taint")}")

        // ---- C4: every request the server saw is in the capture with the same method, URL, status and body hash
        val finalLines = lines(dir)
        val requestLines = finalLines.filter { it.getString("ev") == "request" }
        val statusByRid = finalLines.filter { it.getString("ev") == "completed" || it.getString("ev") == "headers" }.associate { it.getString("rid") to it.getJSONObject("d").optInt("statusCode") }
        val bodyByRid = finalLines.filter { it.getString("ev") == "body" }.associateBy { it.getString("rid") }
        val used = mutableSetOf<String>()
        val table = mutableListOf<String>()
        for (req in serverRequests) {
            val url = server.url(req.toString().substringAfter(' '))
            val match = requestLines.firstOrNull { l ->
                val d = l.getJSONObject("d")
                l.getString("rid") !in used && d.getString("method") == req.method && sameModuloRedaction(url, d.getString("url"))
            }
            assertTrue("no capture record for $req", match != null)
            val rid = match!!.getString("rid")
            used += rid
            assertEquals("status of $req", req.responseStatus, statusByRid[rid])
            val body = bodyByRid[rid]
            val bodyNote = if (body != null) {
                assertEquals("body hash of $req", req.responseSha256, body.getString("sha256"))
                "body sha256 ${body.getString("sha256").take(12)} matches (${body.getLong("size")} bytes, ${body.getInt("chunkCount")} chunks)"
            } else {
                "metadata only (${match.getJSONObject("d").getString("type")})"
            }
            table += "${req.method} ${req.path} -> ${req.responseStatus}; $bodyNote"
        }
        evidence("C4 ${serverRequests.size} server requests, all ${serverRequests.size} found in the capture (${requestLines.size} request records, ${bodyByRid.size} bodies)")
        table.forEach { evidence("C4   $it") }
    }

    @Test
    fun losslessUnderLoad() = runBlocking {
        val n = 200
        val big = 5_500_000
        val small = 4_000
        // Capture off first, for the timing comparison.
        val offTimes = mutableListOf<Long>()
        repeat(2) {
            val run = TestEngine.newRun("c8-off")
            offTimes += loadAndReport("/bulk.html?run=$run&n=$n&size=$small&big=$big&par=6", run).getLong("ms")
        }
        val pssOff = TestEngine.totalPssKiB()

        val id = "c8-${System.currentTimeMillis()}"
        recorder.start(listOf("mock"), id)
        val run = TestEngine.newRun("c8-on")
        val report = loadAndReport("/bulk.html?run=$run&n=$n&size=$small&big=$big&par=6", run)
        val pssOn = TestEngine.totalPssKiB()
        assertEquals(n, report.getInt("done"))
        delay(1_000)
        val t0 = SystemClock.elapsedRealtime()
        val last = recorder.stop()
        val finalizeMs = SystemClock.elapsedRealtime() - t0
        assertTrue(last.message, last.finalized)

        val dir = store.dir(id)
        val finalLines = lines(dir)
        val requests = finalLines.filter { it.getString("ev") == "request" && it.getJSONObject("d").getString("url").contains("/bulk?run=$run") }
        val bodies = finalLines.filter { it.getString("ev") == "body" }.associateBy { it.getString("rid") }
        val serverBulk = server.requests.filter { it.path == "/bulk" && it.query["run"] == run }
        assertEquals(n, serverBulk.size)
        assertEquals(n, requests.size)
        var verified = 0
        var bigOk = false
        for (r in requests) {
            val q = RecordedRequest.parseForm(r.getJSONObject("d").getString("url").substringAfter('?'))
            val i = q.getValue("i").toInt()
            val size = q.getValue("size").toInt()
            val expected = TestEngine.sha256(CapturePages.bulkBody(i, size))
            val body = bodies.getValue(r.getString("rid"))
            assertEquals("bulk $i hash", expected, body.getString("sha256"))
            assertEquals("bulk $i truncated", false, body.getBoolean("truncated"))
            val file = File(dir, body.getString("file"))
            assertEquals("bulk $i stored bytes", expected, TestEngine.sha256(file.readBytes()))
            if (size == big) bigOk = true
            verified++
        }
        assertTrue(bigOk)
        val ext = JSONObject(File(dir, "session.json").readText()).getJSONObject("extensionReport")
        evidence("C8 $n responses (one of $big bytes, ${n - 1} of $small bytes): $verified records with body hashes verified against the served bytes and the stored files; capture page time ${report.getLong("ms")} ms vs off $offTimes ms; finalize $finalizeMs ms; transport ${ext.getJSONObject("transport")}")
        evidence("MEASURE C8 PSS all processes: capture off ${pssOff / 1024} MiB, capture on (after load) ${pssOn / 1024} MiB")
    }

    @Test
    fun fiveMegabyteResponseOverhead() = runBlocking {
        val big = 5_500_000
        val off = mutableListOf<Long>()
        val on = mutableListOf<Long>()
        repeat(3) {
            val r1 = TestEngine.newRun("big-off")
            off += loadAndReport("/bulk.html?run=$r1&n=1&big=$big&par=1", r1).getLong("ms")
        }
        val pssOff = TestEngine.totalPssKiB()
        val id = "big-${System.currentTimeMillis()}"
        recorder.start(listOf("mock"), id)
        repeat(3) {
            val r2 = TestEngine.newRun("big-on")
            on += loadAndReport("/bulk.html?run=$r2&n=1&big=$big&par=1", r2).getLong("ms")
        }
        val pssOn = TestEngine.totalPssKiB()
        val last = recorder.stop()
        assertTrue(last.message, last.finalized)
        evidence("MEASURE 5.5 MB response fetch time ms: capture off $off, capture on $on; PSS off ${pssOff / 1024} MiB, on ${pssOn / 1024} MiB")
    }
}
