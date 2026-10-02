package io.github.chabiroael.twinbook.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.chabiroael.twinbook.engine.TestEngine.evidence
import io.github.chabiroael.twinbook.engine.TestEngine.server
import io.github.chabiroael.twinbook.engine.bridge.BridgeException
import io.github.chabiroael.twinbook.mockserver.MockPages
import io.github.chabiroael.twinbook.mockserver.Scenarios
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * C3: in observe mode the page receives exactly the unfiltered input for every M1 scenario,
 * while the reported decisions equal those of enforce mode. Also: the site profile refuses
 * enforce over the bridge.
 */
@RunWith(AndroidJUnit4::class)
class ObserveModeTest {
    companion object {
        private lateinit var session: EngineSession

        @BeforeClass
        @JvmStatic
        fun setUp() {
            runBlocking { TestEngine.ready() }
            session = TestEngine.newSession(UserAgentProfile.MOBILE, "observe")
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            runBlocking { setMode("mock", "enforce") }
            TestEngine.onMain { session.close() }
        }

        private suspend fun setMode(profile: String, mode: String): JSONObject =
            TestEngine.engine.bridge.request("filter.setMode", JSONObject().put("profile", profile).put("mode", mode))
    }

    private val decisionKeys = listOf("documents", "kept", "dropped", "replaced", "failedOpen", "guard")

    private suspend fun run(scenario: String, mode: String): Map<String, Pair<String, JSONObject>> {
        setMode("mock", mode)
        val run = TestEngine.newRun("$mode-$scenario")
        val report = TestEngine.runTestPage(session, run, "scenario=$scenario&transports=xhr,fetch&summary=1")
        return listOf("xhr", "fetch").associateWith { t ->
            val r = report.getJSONObject("results").getJSONObject(t)
            assertTrue("$scenario/$t/$mode failed: $r", r.getBoolean("ok"))
            val stats = TestEngine.awaitEvent("filter.stats") { it.optString("url").contains("run=$run&transport=$t") }.data
            assertEquals(mode, stats.getString("mode"))
            r.getString("sha256") to stats.getJSONObject("stats")
        }
    }

    @Test
    fun everyScenarioPassesUnchangedWithTheSameDecisions() = runBlocking {
        for (name in Scenarios.names) {
            val s = Scenarios.get(name)
            val enforce = run(name, "enforce")
            val observe = run(name, "observe")
            val unfiltered = TestEngine.sha256(s.body)
            for (t in listOf("xhr", "fetch")) {
                val (eSha, eStats) = enforce.getValue(t)
                val (oSha, oStats) = observe.getValue(t)
                assertEquals("$name/$t enforce output", TestEngine.sha256(s.expectedText), eSha)
                assertEquals("$name/$t observe output must be the unfiltered input", unfiltered, oSha)
                val e = decisionKeys.associateWith { eStats.opt(it) }
                val o = decisionKeys.associateWith { oStats.opt(it) }
                assertEquals("$name/$t decisions", e, o)
                assertEquals("$name/$t observe bytes", oStats.getLong("bytesIn"), oStats.getLong("bytesOut"))
                evidence("C3 $name/$t: observe page sha256 ${oSha.take(16)} == unfiltered ${unfiltered.take(16)} (enforce ${eSha.take(16)}); decisions enforce $e observe $o")
            }
        }
        setMode("mock", "enforce")
        Unit
    }

    @Test
    fun documentIslandsPassUnchangedInObserveMode() = runBlocking {
        val islands = mutableMapOf<String, JSONObject>()
        for (mode in listOf("enforce", "observe")) {
            setMode("mock", mode)
            val run = TestEngine.newRun("doc-$mode")
            withContext(Dispatchers.Main) { session.load(server.url("/document?run=$run")) }
            val report = JSONObject(withContext(Dispatchers.IO) { server.awaitReport(run, 60_000) })
            val stats = TestEngine.awaitEvent("filter.stats") { it.optString("kind") == "document" && it.optString("url").contains("run=$run") }.data
            islands[mode] = stats.getJSONObject("stats")
            val first = report.getJSONArray("islands").getString(0)
            assertEquals(if (mode == "observe") MockPages.DOCUMENT_ISLAND_WITH_ADS else MockPages.DOCUMENT_ISLAND_FILTERED, first)
            assertEquals(MockPages.DOCUMENT_ISLAND_UNTOUCHED, report.getJSONArray("islands").getString(1))
        }
        setMode("mock", "enforce")
        assertEquals(islands.getValue("enforce").getInt("changed"), islands.getValue("observe").getInt("changed"))
        evidence("C3 document: observe islands byte-identical to the input; changed enforce ${islands.getValue("enforce").getInt("changed")} observe ${islands.getValue("observe").getInt("changed")}")
    }

    @Test
    fun siteProfileRefusesEnforce() = runBlocking {
        try {
            setMode("site", "enforce")
            fail("enforce was accepted for the site profile")
        } catch (e: BridgeException) {
            assertEquals("mode_not_allowed", e.code)
            evidence("C17 filter.setMode site enforce -> ${e.code}: ${e.message}")
        }
        val describe = TestEngine.engine.bridge.request("filter.describe")
        evidence("C17 filter.describe: $describe")
        assertEquals(false, describe.getBoolean("siteListening"))
        try {
            TestEngine.engine.bridge.request(
                "replay.fetch",
                JSONObject().put("via", "background").put("request", JSONObject().put("url", "https://www.facebook.com/api/graphql/").put("method", "GET")),
            )
            fail("replay to the site was accepted")
        } catch (e: BridgeException) {
            assertEquals("forbidden_host", e.code)
            evidence("C17 replay.fetch https://www.facebook.com/api/graphql/ -> ${e.code} (nothing sent)")
        }
    }
}
