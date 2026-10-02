package io.github.chabiroael.twinbook.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chabiroael.twinbook.engine.TestEngine.engine
import io.github.chabiroael.twinbook.engine.TestEngine.evidence
import io.github.chabiroael.twinbook.engine.TestEngine.server
import io.github.chabiroael.twinbook.mockserver.CookieKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G15, G16: one phase of the persistence and extension-update checks. Skipped unless the
 * instrumentation argument `persistPhase` is given; tools/persistence-test.sh and
 * tools/extension-update-test.sh drive it with `am instrument`, killing the process between
 * phases and keeping app data.
 *
 * Arguments: persistPhase=write|verify, token=<text>, expectMarker=<marker> (verify only),
 * previousVersion=<version> (verify only).
 */
@RunWith(AndroidJUnit4::class)
class PersistenceProbe {
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun phase() = runBlocking {
        val phase = args.getString("persistPhase")
        assumeTrue("persistence phases run only from tools/ scripts", phase != null)
        val token = args.getString("token") ?: "token"
        val ready = TestEngine.ready()
        val info = engine.bridge.request("extension.info")
        evidence("PERSIST phase=$phase pid=${android.os.Process.myPid()} extension installedVersion=${ready.installedVersion} reportedVersion=${info.getString("version")} marker=${info.getString("marker")}")
        val session = TestEngine.newSession(UserAgentProfile.MOBILE, "persist")
        try {
            when (phase) {
                "write" -> {
                    session.loadAndWait(server.url("/session/set-cookies?prefix=persist&value=$token&maxAge=86400"))
                    session.loadAndWait(server.url("/session/set-cookies?prefix=session&value=$token"))
                    engine.bridge.request("storage.set", JSONObject().put("items", JSONObject().put("persist_token", token)))
                    val back = engine.bridge.request("storage.get", JSONObject().put("keys", JSONArray().put("persist_token")))
                    evidence("PERSIST write: cookies persist_*=$token (Max-Age=86400) and session_*=$token (no expiry) set; storage.local persist_token=${back.getJSONObject("items").optString("persist_token")}")
                    // Give Gecko time to flush the cookie database and storage to disk.
                    delay(5_000)
                }
                "verify" -> {
                    val run = TestEngine.newRun("persist")
                    session.loadAndWait(server.url("/session/echo?run=$run"))
                    val cookies = server.requestsTo("/session/echo").last { it.query["run"] == run }.cookies
                    val persistent = CookieKind.entries.associate { it.suffix to cookies[it.cookieName("persist")] }
                    val sessionOnly = CookieKind.entries.associate { it.suffix to cookies[it.cookieName("session")] }
                    val stored = engine.bridge.request("storage.get", JSONObject().put("keys", JSONArray().put("persist_token")))
                        .getJSONObject("items").optString("persist_token", "(missing)")
                    evidence("PERSIST verify: persistent cookies after restart $persistent")
                    evidence("PERSIST verify: session cookies after restart $sessionOnly")
                    evidence("PERSIST verify: storage.local persist_token=$stored (expected $token)")
                    for ((kind, value) in persistent) assertEquals("persistent cookie $kind", token, value)
                    assertEquals(token, stored)
                    args.getString("expectMarker")?.let {
                        assertEquals("extension marker", it, info.getString("marker"))
                        evidence("PERSIST verify: extension marker is the new build's: $it")
                    }
                    args.getString("previousVersion")?.let {
                        assertNotEquals("extension version", it, info.getString("version"))
                        assertEquals(info.getString("version"), ready.installedVersion)
                        evidence("PERSIST verify: extension version changed $it -> ${info.getString("version")}")
                    }
                    evidence("PERSIST verify: PASS")
                }
                else -> throw IllegalArgumentException("unknown persistPhase $phase")
            }
        } finally {
            TestEngine.onMain { session.close() }
        }
    }
}
