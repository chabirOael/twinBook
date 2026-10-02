package io.github.chabiroael.twinbook.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.chabiroael.twinbook.engine.TestEngine.evidence
import io.github.chabiroael.twinbook.engine.TestEngine.server
import io.github.chabiroael.twinbook.mockserver.CookieKind
import io.github.chabiroael.twinbook.mockserver.RecordedRequest
import io.github.chabiroael.twinbook.mockserver.Scenarios
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** G14: sessions with different user agents share cookies; a headless session works fully. */
@RunWith(AndroidJUnit4::class)
class SessionsTest {
    private suspend fun echoFrom(session: EngineSession, run: String): RecordedRequest {
        session.loadAndWait(server.url("/session/echo?run=$run"))
        return server.requestsTo("/session/echo").last { it.query["run"] == run }
    }

    @Test
    fun mobileAndDesktopSessionsShareCookies() = runBlocking {
        TestEngine.ready()
        val mobile = TestEngine.newSession(UserAgentProfile.MOBILE, "mobile")
        val desktop = TestEngine.newSession(UserAgentProfile.DESKTOP, "desktop")
        try {
            val run = TestEngine.newRun("g14")
            mobile.loadAndWait(server.url("/session/set-cookies?prefix=m$run&value=fromMobile"))
            desktop.loadAndWait(server.url("/session/set-cookies?prefix=d$run&value=fromDesktop"))
            val seenByDesktop = echoFrom(desktop, "$run-d")
            val seenByMobile = echoFrom(mobile, "$run-m")

            val mobileUa = seenByMobile.header("User-Agent").orEmpty()
            val desktopUa = seenByDesktop.header("User-Agent").orEmpty()
            assertNotEquals(mobileUa, desktopUa)
            assertTrue(mobileUa, mobileUa.contains("Mobile"))
            assertTrue(desktopUa, !desktopUa.contains("Mobile"))
            assertEquals(mobileUa, mobile.userAgent())
            assertEquals(desktopUa, desktop.userAgent())

            for (kind in CookieKind.entries) {
                assertEquals("desktop sees mobile's ${kind.suffix}", "fromMobile", seenByDesktop.cookies[kind.cookieName("m$run")])
                assertEquals("mobile sees desktop's ${kind.suffix}", "fromDesktop", seenByMobile.cookies[kind.cookieName("d$run")])
            }
            evidence("G14 mobile UA (server saw): $mobileUa")
            evidence("G14 desktop UA (server saw): $desktopUa")
            evidence("G14 desktop session sent mobile-set cookies: ${seenByDesktop.cookies.filterKeys { it.startsWith("m$run") }}")
            evidence("G14 mobile session sent desktop-set cookies: ${seenByMobile.cookies.filterKeys { it.startsWith("d$run") }}")
        } finally {
            TestEngine.onMain {
                mobile.close()
                desktop.close()
            }
        }
    }

    @Test
    fun headlessSessionLoadsRequestsAndGetsFilteredResponses() = runBlocking {
        TestEngine.ready()
        val headless = TestEngine.newSession(UserAgentProfile.DESKTOP, "headless")
        try {
            assertTrue(headless.isHeadless)
            val run = TestEngine.newRun("headless")
            val report = TestEngine.runTestPage(headless, run, "scenario=ads-all&transports=xhr,fetch&timers=3000&integrity=1")
            val expected = Scenarios.get("ads-all").expectedText
            for (t in listOf("xhr", "fetch")) {
                assertEquals("headless $t", expected, report.getJSONObject("results").getJSONObject(t).getString("text"))
            }
            val timers = report.getJSONObject("timers")
            assertTrue("timers throttled: $timers", timers.getInt("ticks") >= 25)
            assertTrue(report.getJSONObject("integrity").toString(), report.getJSONObject("integrity").getBoolean("ok"))
            val pageRequests = server.requests.filter { it.query["run"] == run }.map { "${it.method} ${it.path}" }
            evidence("G14 headless session (never attached to a view): server saw $pageRequests")
            evidence("G14 headless: xhr and fetch got the filtered text; 100 ms interval over 3 s: $timers; integrity ok")
        } finally {
            TestEngine.onMain { headless.close() }
        }
    }
}
