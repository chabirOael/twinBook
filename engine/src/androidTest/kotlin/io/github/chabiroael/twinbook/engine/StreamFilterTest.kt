package io.github.chabiroael.twinbook.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.chabiroael.twinbook.engine.TestEngine.evidence
import io.github.chabiroael.twinbook.engine.TestEngine.server
import io.github.chabiroael.twinbook.mockserver.Scenarios
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G4 to G9, G11 to G13: the page requests streamed GraphQL-like responses through
 * XMLHttpRequest (progress events) and fetch (stream reader). twin-bridge filters them in the
 * background script; the page must receive exactly the expected bytes.
 */
@RunWith(AndroidJUnit4::class)
class StreamFilterTest {
    companion object {
        private lateinit var session: EngineSession

        @BeforeClass
        @JvmStatic
        fun setUp() {
            runBlocking { TestEngine.ready() }
            session = TestEngine.newSession(UserAgentProfile.MOBILE, "stream-filter")
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            TestEngine.onMain { session.close() }
        }
    }

    /** Runs [scenario] through both transports and checks the page got the expected text. */
    private fun runScenario(scenario: String, transports: List<String> = listOf("xhr", "fetch"), extra: String = ""): Pair<String, JSONObject> = runBlocking {
        val s = Scenarios.get(scenario)
        val run = TestEngine.newRun(scenario)
        val summary = s.body.size > 1_000_000
        val report = TestEngine.runTestPage(
            session,
            run,
            "scenario=$scenario&transports=${transports.joinToString(",")}${if (summary) "&summary=1" else ""}$extra",
        )
        val expectedSha = TestEngine.sha256(s.expectedText)
        for (t in transports) {
            val r = report.getJSONObject("results").getJSONObject(t)
            assertTrue("$scenario/$t failed: $r", r.getBoolean("ok"))
            assertEquals("$scenario/$t status", 200, r.getInt("status"))
            if (!summary) assertEquals("$scenario/$t text", s.expectedText, r.getString("text"))
            assertEquals("$scenario/$t sha256", expectedSha, r.getString("sha256"))
            val stats = statsFor(run, t)
            evidence(
                "$scenario/$t: received ${r.getInt("length")} chars in ${r.getInt("lineCount")} lines, sha256 matches expected " +
                    "(input ${s.body.size} bytes, ${s.adIds.size} ads); filter stats ${stats.getJSONObject("stats")} chunks=${stats.getInt("chunks")} busyMs=${stats.get("busyMs")}",
            )
        }
        run to report
    }

    private suspend fun statsFor(run: String, transport: String): JSONObject =
        TestEngine.awaitEvent("filter.stats") { it.optString("url").contains("run=$run&transport=$transport") }.data

    @Test
    fun adAtFirstPosition() {
        runScenario("ads-first")
    }

    @Test
    fun adsInTheMiddle() {
        runScenario("ads-middle")
    }

    @Test
    fun adInTheFinalDocumentIsReplaced() {
        val (run, report) = runScenario("ads-last")
        for (t in listOf("xhr", "fetch")) {
            val text = report.getJSONObject("results").getJSONObject(t).getString("text")
            assertEquals(Scenarios.FINAL_REPLACEMENT, text.trimEnd('\n').substringAfterLast('\n'))
            runBlocking { assertEquals(1, statsFor(run, t).getJSONObject("stats").getInt("replaced")) }
        }
    }

    @Test
    fun adsAtAllPositions() {
        val (run, _) = runScenario("ads-all")
        runBlocking {
            val stats = statsFor(run, "xhr").getJSONObject("stats")
            assertEquals(1, stats.getInt("dropped"))
            assertEquals(2, stats.getInt("replaced"))
            assertTrue(stats.getBoolean("guard"))
        }
    }

    @Test
    fun noAdsMeansUnchanged() {
        runScenario("no-ads")
    }

    @Test
    fun withoutGuardPrefix() {
        val (run, _) = runScenario("no-guard")
        runBlocking { assertEquals(false, statsFor(run, "fetch").getJSONObject("stats").getBoolean("guard")) }
    }

    @Test
    fun chunkBoundaryInsideALine() {
        val (run, _) = runScenario("split-line")
        runBlocking { assertTrue(statsFor(run, "xhr").getInt("chunks") > 1) }
    }

    @Test
    fun chunkBoundaryInsideAMultibyteCharacter() {
        val (run, _) = runScenario("split-utf8")
        runBlocking { evidence("split-utf8: server cut ${Scenarios.get("split-utf8").cuts.size} times inside characters; filter saw ${statsFor(run, "xhr").getInt("chunks")} chunks") }
    }

    @Test
    fun gzipEncodedResponse() {
        val (run, _) = runScenario("gzip")
        val req = server.requestsTo("/api/graphql/").last { it.query["run"] == run }
        evidence("gzip: request Accept-Encoding=${req.header("Accept-Encoding")}; response was Content-Encoding: gzip")
    }

    @Test
    fun malformedLineFailsOpen() {
        val (run, _) = runScenario("malformed")
        runBlocking {
            for (t in listOf("xhr", "fetch")) {
                val error = TestEngine.awaitEvent("filter.error") { it.optString("url").contains("run=$run&transport=$t") }
                val e = error.data.getJSONObject("error")
                assertEquals("parse", e.getString("kind"))
                assertEquals(Scenarios.get("malformed").malformedLines.single(), e.getString("sample"))
                evidence("malformed/$t: app received filter.error ${error.data}")
            }
        }
    }

    /** G7: the page has the first line before the server has sent the last chunk. */
    @Test
    fun progressiveDelivery() {
        val (run, report) = runScenario("slow")
        for (t in listOf("xhr", "fetch")) {
            val r = report.getJSONObject("results").getJSONObject(t)
            val trace = server.streamTraces.single { it.run == run && it.transport == t }
            val firstLineAt = r.getLong("firstLineAt")
            val lastChunkAt = trace.chunkSentAtMillis.last()
            assertTrue("$t: first line at $firstLineAt, last chunk at $lastChunkAt", firstLineAt < lastChunkAt)
            val lineTimes = r.getJSONArray("lineTimes")
            evidence(
                "G7 slow/$t: server chunk times ${trace.chunkSentAtMillis.map { it - trace.startedAtMillis }} ms after start; " +
                    "page line times ${(0 until lineTimes.length()).map { lineTimes.getLong(it) - trace.startedAtMillis }} ms; " +
                    "first line ${lastChunkAt - firstLineAt} ms before the last chunk was sent",
            )
        }
    }

    /** G9: 5 MB response, filtered; delivery time with the filter on and off. */
    @Test
    fun largeResponseAndFilterCost() = runBlocking {
        runScenario("large")
        val bridge = TestEngine.engine.bridge
        val on = mutableListOf<Long>()
        val off = mutableListOf<Long>()
        val busy = mutableListOf<Double>()
        try {
            repeat(3) {
                bridge.request("filter.setEnabled", JSONObject().put("enabled", true))
                val (run, report) = runScenario("large")
                on += durations(report)
                busy += listOf("xhr", "fetch").map { t -> statsFor(run, t).getDouble("busyMs") }
                bridge.request("filter.setEnabled", JSONObject().put("enabled", false))
                val offRun = TestEngine.newRun("large-off")
                val offReport = TestEngine.runTestPage(session, offRun, "scenario=large&transports=xhr,fetch&summary=1")
                for (t in listOf("xhr", "fetch")) {
                    assertEquals(TestEngine.sha256(Scenarios.get("large").bodyText), offReport.getJSONObject("results").getJSONObject(t).getString("sha256"))
                }
                off += durations(offReport)
            }
        } finally {
            bridge.request("filter.setEnabled", JSONObject().put("enabled", true))
        }
        evidence("G9 5 MB delivery ms (xhr,fetch pairs) filter on: $on; filter off: $off; filter busy ms per response: $busy")
        evidence("G9 median ms: on ${on.sorted()[on.size / 2]}, off ${off.sorted()[off.size / 2]}")
    }

    private fun durations(report: JSONObject): List<Long> = listOf("xhr", "fetch").map {
        val r = report.getJSONObject("results").getJSONObject(it)
        r.getLong("finishedAt") - r.getLong("startedAt")
    }

    /** G13: the app receives the form fields of every POST to the GraphQL path. */
    @Test
    fun recorderSendsFormFields() {
        val (run, _) = runScenario("ads-first")
        runBlocking {
            for (t in listOf("xhr", "fetch")) {
                val event = TestEngine.awaitEvent("recorder.request") { it.optString("url").contains("run=$run&transport=$t") }.data
                val fields = event.getJSONObject("fields")
                val serverSide = server.requestsTo("/api/graphql/").single { it.query["run"] == run && it.query["transport"] == t }
                for (name in listOf("fb_api_req_friendly_name", "doc_id", "variables", "fb_dtsg", "lsd", "jazoest", "__rev", "__req")) {
                    assertEquals("$t field $name", serverSide.form[name], fields.getString(name))
                }
                val names = event.getJSONArray("fieldNames")
                assertEquals(serverSide.formFieldNames, (0 until names.length()).map { names.getString(it) })
                assertEquals("MOCK_DTSG:$run", fields.getString("fb_dtsg"))
                evidence("G13 recorder/$t: source=${event.getString("source")} fields=$fields fieldNames=$names")
            }
        }
    }

    /** G12: integrity checks pass in the page while filtering is active. */
    @Test
    fun integrityChecksPassWhileFiltering() {
        val (run, report) = runScenario("ads-all", extra = "&integrity=1")
        val integrity = report.getJSONObject("integrity")
        assertTrue("integrity failures: ${integrity.getJSONArray("failed")}", integrity.getBoolean("ok"))
        runBlocking {
            val doc = TestEngine.awaitEvent("filter.stats") { it.optString("kind") == "document" && it.optString("url").contains("run=$run") }
            evidence("G12 the test page itself went through the document filter: ${doc.data.getJSONObject("stats")}")
        }
        evidence("G12 integrity: ${integrity.getInt("count")} checks, all passed")
        val checks = integrity.getJSONArray("checks")
        for (i in 0 until checks.length()) {
            val c = checks.getJSONObject(i)
            evidence("G12 check ${if (c.getBoolean("ok")) "pass" else "FAIL"}: ${c.getString("name")}")
        }
    }
}
