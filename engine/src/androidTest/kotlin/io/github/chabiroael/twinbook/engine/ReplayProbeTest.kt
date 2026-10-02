package io.github.chabiroael.twinbook.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.chabiroael.twinbook.engine.TestEngine.engine
import io.github.chabiroael.twinbook.engine.TestEngine.evidence
import io.github.chabiroael.twinbook.engine.TestEngine.server
import io.github.chabiroael.twinbook.mockserver.CookieKind
import io.github.chabiroael.twinbook.mockserver.MockServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G17, G18: probes of the two candidate replay paths for M5. Records exactly what the mock
 * server received for each path, under the engine's default and strict tracking protection.
 * These are observations: only the plumbing (a 200 response) is asserted.
 */
@RunWith(AndroidJUnit4::class)
class ReplayProbeTest {
    private val padUnits = 400

    private data class Probe(val label: String, val via: String, val rewrite: JSONObject?)

    @Test
    fun replayPathsDefaultAndStrict() = runBlocking {
        TestEngine.ready()
        evidence("PROBE GeckoView default content blocking: ${engine.describeDefaultContentBlocking()}")
        try {
            for (level in TrackingProtection.entries) probeUnder(level)
        } finally {
            withContext(Dispatchers.Main) { engine.setTrackingProtection(TrackingProtection.DEFAULT) }
            engine.bridge.request("replay.configure", JSONObject().put("rewrite", JSONObject.NULL))
        }
    }

    private suspend fun probeUnder(level: TrackingProtection) {
        withContext(Dispatchers.Main) { engine.setTrackingProtection(level) }
        val tag = level.name.lowercase()
        val run = TestEngine.newRun("probe-$tag")
        val visible = TestEngine.newSession(UserAgentProfile.MOBILE, "probe-mobile")
        val other = TestEngine.newSession(UserAgentProfile.DESKTOP, "probe-desktop")
        val anchor = TestEngine.newSession(UserAgentProfile.DESKTOP, "probe-anchor")
        val sessionSettings = TestEngine.onMain { visible.geckoSession.settings.useTrackingProtection }
        evidence("PROBE [$tag] runtime content blocking ${engine.describeContentBlocking()}; session useTrackingProtection=$sessionSettings")
        try {
            // Cookies of every kind, set by two different sessions.
            visible.loadAndWait(server.url("/session/set-cookies?prefix=a$tag&value=1"))
            other.loadAndWait(server.url("/session/set-cookies?prefix=b$tag&value=1"))
            // The anchor page, in a headless session.
            anchor.loadAndWait(server.url("/anchor"))
            withTimeout(20_000) {
                while (engine.bridge.request("replay.anchors").getInt("count") == 0) delay(200)
            }

            val siteOrigin = server.origin
            val probes = listOf(
                Probe("A background fetch", "background", null),
                Probe("A background fetch + Origin/Referer rewrite", "background", JSONObject().put("origin", siteOrigin).put("referer", "$siteOrigin/")),
                Probe("B anchor content.fetch", "anchor", null),
                Probe("B' anchor content-script fetch (extension principal)", "anchor-extension-fetch", null),
            )
            evidence("PROBE [$tag] | path | cookies sent (set by session A / by session B) | Origin | Referer | Sec-Fetch-Site | Sec-Fetch-Mode | Sec-Fetch-Dest | User-Agent | body to Kotlin |")
            for ((i, probe) in probes.withIndex()) {
                engine.bridge.request("replay.configure", JSONObject().put("rewrite", probe.rewrite ?: JSONObject.NULL))
                val id = "$run-$i"
                val url = server.url("/session/echo?twinbook_probe=$id&pad=$padUnits")
                val request = JSONObject()
                    .put("url", url)
                    .put("method", "POST")
                    .put("headers", JSONObject().put("Content-Type", "application/x-www-form-urlencoded"))
                    .put("body", "doc_id=1&variables=%7B%7D")
                    .put("credentials", "include")
                val result = engine.bridge.request("replay.fetch", JSONObject().put("via", probe.via).put("request", request), 30_000)
                assertEquals(200, result.getInt("status"))
                val received = withContext(Dispatchers.IO) { server.requestsTo("/session/echo").single { it.query["twinbook_probe"] == id } }
                val echo = JSONObject(result.getString("body"))
                val intact = echo.optString("payload") == MockServer.payload(padUnits) && echo.optString("body") == "doc_id=1&variables=%7B%7D"
                val cookies = fun(prefix: String) = CookieKind.entries.filter { received.cookies.containsKey(it.cookieName(prefix)) }.joinToString(",") { it.suffix }.ifEmpty { "none" }
                val h = { name: String -> received.header(name) ?: "(absent)" }
                evidence(
                    "PROBE [$tag] | ${probe.label} | A: ${cookies("a$tag")} / B: ${cookies("b$tag")} | ${h("Origin")} | ${h("Referer")} | " +
                        "${h("Sec-Fetch-Site")} | ${h("Sec-Fetch-Mode")} | ${h("Sec-Fetch-Dest")} | ${h("User-Agent")} | " +
                        "${if (intact) "intact" else "NOT intact"} (${result.getString("body").length} chars) |",
                )
                evidence("PROBE [$tag] ${probe.label}: extension saw sent headers ${result.opt("sentHeaders")}")
            }
        } finally {
            TestEngine.onMain {
                visible.close()
                other.close()
                anchor.close()
            }
        }
    }
}
