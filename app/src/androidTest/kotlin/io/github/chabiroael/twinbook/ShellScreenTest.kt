package io.github.chabiroael.twinbook

import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chabiroael.twinbook.engine.BlockerState
import io.github.chabiroael.twinbook.engine.await
import io.github.chabiroael.twinbook.mockserver.MockServer
import io.github.chabiroael.twinbook.mockserver.ShellPages
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoView

/**
 * S4 to S7 and S9: the web shell on the mock. Start-up splash then page, back, reload, home and
 * the menu; rotation without reload; the system's dark scheme reaching the page; a crashed
 * content process and its recovery; the link rules; uBlock Origin blocking a listed request and
 * hiding a listed element, and both coming back with ad hiding off; strict mode. External apps
 * are never started: the shell gets an opener that only records.
 */
@RunWith(AndroidJUnit4::class)
class ShellScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var server: MockServer
    private lateinit var shell: Shell
    private val opener = RecordingOpener()
    private val run = "" // the start page carries no run id; each test has its own server

    class RecordingOpener : ExternalOpener {
        val browser = CopyOnWriteArrayList<String>()
        val system = CopyOnWriteArrayList<String>()

        override fun openInBrowser(url: String) {
            browser += url
        }

        override fun openWithSystem(url: String) {
            system += url
        }
    }

    @Before
    fun setUp() {
        server = MockServer().start()
        instrumentation.runOnMainSync {
            ShellSettings.get(instrumentation.targetContext).setStrict(false)
            shell = Shell.configureForTest(instrumentation.targetContext, ShellConfig.mock(server.origin), opener)
        }
        // The saved state of an earlier test (another server, another site id) is never restored.
        compose.onNodeWithTag("open-shell").performClick()
        compose.onNodeWithTag("shell").assertIsDisplayed()
    }

    @After
    fun tearDown() {
        try {
            // Leave the process-wide settings as the next test expects them.
            if (!shell.settings.adHiding.value) runBlocking(Dispatchers.Main) { shell.setAdHiding(true) }
            if (shell.settings.strict.value) runBlocking(Dispatchers.Main) { shell.setStrict(false) }
        } finally {
            server.close()
        }
    }

    private fun evidence(line: String) = Log.i("twinbook-evidence", line)

    private fun shellCmd(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun waitFor(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what; page ${shell.session.page.value}; logs ${server.logs(run)}")
            Thread.sleep(100)
        }
    }

    private fun requests(path: String) = server.requests.count { it.path == path }

    private fun logs(field: String) = server.logs(run).filter { it.first == field }.map { it.second }

    private fun loaded(page: String) = logs("loaded").count { it == page }

    private fun geckoView(): GeckoView {
        fun View.find(): GeckoView? = when (this) {
            is GeckoView -> this
            is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).find() }
            else -> null
        }
        return compose.activity.window.decorView.find() ?: throw AssertionError("no GeckoView")
    }

    private fun lastLayout(): JSONObject = JSONObject(logs("layout").last())

    /** Taps the centre of element [id] using the page-reported layout. */
    private fun tap(id: String) {
        compose.waitForIdle()
        val layout = lastLayout()
        val r = layout.getJSONObject("els").getJSONArray(id)
        val view = geckoView()
        val loc = IntArray(2)
        instrumentation.runOnMainSync { view.getLocationOnScreen(loc) }
        val scale = view.width / layout.getDouble("vw")
        val x = loc[0] + (r.getDouble(0) + r.getDouble(2) / 2) * scale
        val y = loc[1] + (r.getDouble(1) + r.getDouble(3) / 2) * scale
        shellCmd("input tap ${x.toInt()} ${y.toInt()}")
    }

    private fun awaitHome(times: Int = 1) {
        waitFor("home loaded $times") { loaded("home") >= times && logs("layout").size >= times }
        waitFor("home shown") { shell.shown.value && !shell.session.page.value.loading }
        // The splash goes away on the next frame; taps sent before that can be lost.
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("shell-splash")).fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        Thread.sleep(700)
    }

    /** The system back key, once Compose has caught up with the page's history (its back handlers). */
    private fun pressBack() {
        compose.waitForIdle()
        Thread.sleep(300)
        shellCmd("input keyevent KEYCODE_BACK")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        Thread.sleep(800)
        shellCmd("screencap -p /data/local/tmp/$name.png")
        evidence("screenshot /data/local/tmp/$name.png")
    }

    @Test
    fun splashThenPageBackReloadHomeAndMenu() {
        // The splash covers the screen until the engine is ready and the first page is drawn.
        compose.onNodeWithTag("shell-splash").assertIsDisplayed()
        assertFalse(shell.shown.value)
        awaitHome()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("shell-splash")).fetchSemanticsNodes().isEmpty() }
        assertEquals(server.url("/shell/home.html"), shell.session.page.value.url)
        evidence("S4 splash shown first, then the start page ${shell.session.page.value.url}")
        screenshot("twinbook-s4-shell-light")

        tap("feed")
        waitFor("feed") { loaded("feed") == 1 }
        waitFor("can go back") { shell.session.page.value.canGoBack }
        pressBack()
        waitFor("back on home") { shell.session.page.value.url == server.url("/shell/home.html") && !shell.session.page.value.loading }
        assertTrue("still in the app", shellCmd("dumpsys activity activities").lines().any { "ResumedActivity" in it && instrumentation.targetContext.packageName in it })
        evidence("S4 back: feed -> home in the page's history, app stays in front")

        val homeRequests = requests("/shell/home.html")
        compose.onNodeWithTag("shell-menu").performClick()
        compose.onNodeWithTag("menu-reload").performClick()
        waitFor("reload") { requests("/shell/home.html") == homeRequests + 1 && !shell.session.page.value.loading }
        evidence("S4 menu reload: home requested ${homeRequests} -> ${requests("/shell/home.html")} times")

        instrumentation.runOnMainSync { shell.session.load(server.url("/shell/feed.html")) }
        waitFor("feed again") { loaded("feed") == 2 }
        compose.onNodeWithTag("shell-menu").performClick()
        compose.onNodeWithTag("menu-home").performClick()
        waitFor("home via menu") { shell.session.page.value.url == server.url("/shell/home.html") && !shell.session.page.value.loading }
        evidence("S4 menu home: back on ${shell.session.page.value.url}")

        compose.onNodeWithTag("shell-menu").performClick()
        compose.onNodeWithTag("menu-settings").performClick()
        compose.onNodeWithTag("settings").assertIsDisplayed()
        val versions = compose.onNodeWithTag("setting-versions").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString(" ")
        assertTrue(versions, versions.contains("uBlock Origin 1.75.0"))
        assertTrue(versions, versions.contains("GPL-3.0"))
        assertTrue(versions, versions.contains("https://github.com/gorhill/uBlock"))
        evidence("S4 settings versions: ${versions.replace("\n", " | ")}")
        pressBack()
        compose.onNodeWithTag("shell").assertIsDisplayed()
    }

    @Test
    fun rotationDoesNotReloadAndDarkSchemeReachesThePage() {
        awaitHome()
        instrumentation.runOnMainSync { shell.session.load(server.url("/shell/dark.html")) }
        waitFor("dark page") { loaded("dark") == 1 && logs("scheme").isNotEmpty() }
        val initialNight = shellCmd("cmd uimode night").trim()
        val loads = shell.session.page.value.loadCount
        val darkRequests = requests("/shell/dark.html")
        val activity = compose.activity
        try {
            shellCmd("settings put system accelerometer_rotation 0")
            shellCmd("settings put system user_rotation 1")
            waitFor("landscape", 10_000) { activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            Thread.sleep(1_500)
            assertEquals("no reload on rotation", loads, shell.session.page.value.loadCount)
            assertEquals(darkRequests, requests("/shell/dark.html"))
            assertTrue("same activity", compose.activity === activity)
            evidence("S4 rotation to landscape: loadCount $loads unchanged, dark.html requested $darkRequests time(s), activity not recreated")
            shellCmd("settings put system user_rotation 0")
            waitFor("portrait", 10_000) { activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }

            shellCmd("cmd uimode night yes")
            waitFor("page reports dark") { logs("scheme").last() == "dark" }
            screenshot("twinbook-s4-shell-dark")
            shellCmd("cmd uimode night no")
            waitFor("page reports light") { logs("scheme").last() == "light" }
            assertEquals("no reload on theme change", loads, shell.session.page.value.loadCount)
            evidence("S4 dark scheme: page reported ${logs("scheme")} while the system went dark and back to light, without a reload (loadCount $loads)")
        } finally {
            shellCmd("settings put system user_rotation 0")
            shellCmd("cmd uimode night ${if (initialNight.contains("yes")) "yes" else "no"}")
        }
    }

    @Test
    fun crashedContentProcessShowsRecoveryAndReloads() {
        awaitHome()
        instrumentation.runOnMainSync { shell.session.load(server.url("/shell/feed.html")) }
        waitFor("feed") { loaded("feed") == 1 && !shell.session.page.value.loading }
        Thread.sleep(800) // let Gecko report the session state of the feed page
        val feedRequests = requests("/shell/feed.html")
        // Gecko's own crash test page crashes the content process that loads it.
        instrumentation.runOnMainSync { shell.session.load("about:crashcontent") }
        waitFor("crash reported", 30_000) { shell.session.page.value.crashed }
        compose.onNodeWithTag("shell-crashed").assertIsDisplayed()
        evidence("S5 about:crashcontent: session crashed=${shell.session.page.value.crashed}, recovery view shown")
        compose.onNodeWithTag("shell-recover").performClick()
        waitFor("feed again after recovery", 30_000) { requests("/shell/feed.html") > feedRequests && !shell.session.page.value.loading && !shell.session.page.value.crashed }
        assertEquals(server.url("/shell/feed.html"), shell.session.page.value.url)
        assertEquals(1, shell.session.page.value.recoveries)
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("shell-crashed")).fetchSemanticsNodes().isEmpty() }
        evidence("S5 recovery: session reopened and its last page reloaded (${shell.session.page.value.url}), feed requested $feedRequests -> ${requests("/shell/feed.html")}")
    }

    @Test
    fun linksStayLeaveUnwrappedOrGoToTheSystem() {
        awaitHome()
        tap("outbound")
        waitFor("outbound opened") { opener.browser.size == 1 }
        assertEquals(ShellPages.OUTBOUND_CLEAN, opener.browser[0])
        tap("direct")
        waitFor("direct opened") { opener.browser.size == 2 }
        assertEquals(ShellPages.DIRECT_CLEAN, opener.browser[1])
        tap("newwin")
        waitFor("new window opened") { opener.browser.size == 3 }
        assertEquals(ShellPages.NEW_WINDOW_CLEAN, opener.browser[2])
        for (id in listOf("tel", "mailto", "geo")) {
            val before = opener.system.size
            tap(id)
            waitFor("$id handed to the system") { opener.system.size == before + 1 }
        }
        assertEquals(listOf("tel:+15550100", "mailto:someone@example.com", "geo:25.28,51.53"), opener.system.toList())
        Thread.sleep(1_000)
        assertEquals("the redirect page was never requested", 0, requests("/l.php"))
        assertEquals(server.url("/shell/home.html"), shell.session.page.value.url)
        evidence("S6 outbound: browser got ${opener.browser}; system got ${opener.system}; /l.php requested ${requests("/l.php")} times; shell still on ${shell.session.page.value.url}")
        evidence("S6 server log: ${server.requests.map { it.toString() }}")

        tap("feed")
        waitFor("internal link stays") { loaded("feed") == 1 && shell.session.page.value.url == server.url("/shell/feed.html?run=") }
        assertEquals(3, opener.browser.size)
        evidence("S6 internal link stayed in the shell: ${shell.session.page.value.url}")

        // Back goes back in the page's history; with nothing left, it leaves the shell (in the
        // debug build to the developer start screen; in the daily build the system takes it).
        pressBack()
        waitFor("back on home") { shell.session.page.value.url == server.url("/shell/home.html") && !shell.session.page.value.loading && !shell.session.page.value.canGoBack }
        pressBack()
        compose.onNodeWithTag("open-shell").assertIsDisplayed()
        evidence("S4 back: feed -> home, then back with no history left the shell")
    }

    @Test
    fun ublockBlocksAndHidesAndBothComeBackWithAdHidingOff() {
        awaitHome()
        val blocker = shell.blocker.value
        assertTrue("blocker ready: $blocker", blocker is BlockerState.Ready)
        // EasyList turns generic cosmetic filters off on 127.0.0.1 ($generichide); the debug build
        // resolves the mock's host name to the loopback interface instead.
        val adsUrl = "http://${ShellPages.NAMED_HOST}:${server.port}/shell/ads.html"
        instrumentation.runOnMainSync { shell.session.load(adsUrl) }
        waitFor("ads page settled", 30_000) { logs("cosmetic-done").size == 1 }
        val on = JSONObject(logs("cosmetic-done").last())
        assertEquals("listed request blocked", 0, requests("/__utm.gif"))
        assertFalse("element listed by id hidden: $on", on.getBoolean("banner"))
        assertFalse("element listed by class hidden: $on", on.getBoolean("byClass"))
        assertTrue("control element shown: $on", on.getBoolean("content"))
        evidence("S7 ad hiding on: /__utm.gif requested ${requests("/__utm.gif")} times; page reports $on")

        compose.onNodeWithTag("shell-menu").performClick()
        compose.onNodeWithTag("menu-settings").performClick()
        compose.onNodeWithTag("setting-ad-hiding").performClick()
        waitFor("blocker off", 30_000) { shell.blocker.value is BlockerState.Disabled }
        pressBack()
        waitFor("ads page again", 30_000) { logs("cosmetic-done").size == 2 }
        val off = JSONObject(logs("cosmetic-done").last())
        assertTrue("listed request went out with ad hiding off", requests("/__utm.gif") >= 1)
        assertTrue("element by id shown: $off", off.getBoolean("banner"))
        assertTrue("element by class shown: $off", off.getBoolean("byClass"))
        evidence("S7 ad hiding off (uBlock Origin disabled, not reinstalled): /__utm.gif requested ${requests("/__utm.gif")} time(s); page reports $off")

        val gifs = requests("/__utm.gif")
        runBlocking(Dispatchers.Main) { shell.setAdHiding(true) }
        assertTrue(shell.blocker.value is BlockerState.Ready)
        waitFor("ads page a third time", 30_000) { logs("cosmetic-done").size == 3 }
        val again = JSONObject(logs("cosmetic-done").last())
        assertEquals(gifs, requests("/__utm.gif"))
        assertFalse(again.getBoolean("banner"))
        evidence("S7 ad hiding on again: ${shell.blocker.value}; no new /__utm.gif request; page reports $again")
    }

    @Test
    fun ublockDashboardOpensAndReportsAMobileEnvironment() {
        awaitHome()
        val ext = shell.engine.blockerWebExtension!!
        assertTrue(ext.metaData.baseUrl, ext.metaData.baseUrl.startsWith("moz-extension://"))
        // The dashboard entry opens uBlock Origin's options page inside the app.
        compose.onNodeWithTag("shell-menu").performClick()
        compose.onNodeWithTag("menu-settings").performClick()
        compose.onNodeWithTag("setting-dashboard").performClick()
        compose.onNodeWithTag("dashboard").assertIsDisplayed()
        waitFor("options page loaded", 30_000) { shell.dashboardSession?.page?.value?.let { it.url?.startsWith(ext.metaData.optionsPageUrl!!) == true && !it.loading } == true }
        screenshot("twinbook-s7-dashboard")
        evidence("S7 dashboard entry opened ${shell.dashboardSession?.page?.value?.url} (options page ${ext.metaData.optionsPageUrl})")
        pressBack()
        compose.onNodeWithTag("settings").assertIsDisplayed()

        // uBlock Origin's support page names the browser family it detected in its
        // troubleshooting information: "Firefox Mobile" only when it considers itself on mobile.
        // The text is drawn by a code editor that renders only in a visible view, so the page is
        // opened through the dashboard screen.
        DevOverrides.dashboardPage = "support.html"
        try {
            compose.onNodeWithTag("setting-dashboard").performClick()
            waitFor("support page", 30_000) { shell.dashboardSession?.page?.value?.let { it.url?.endsWith("support.html") == true && !it.loading } == true }
            var total = 0
            waitFor("troubleshooting information names the environment", 30_000) {
                val s = shell.dashboardSession ?: return@waitFor false
                total = runBlocking(Dispatchers.Main) { s.geckoSession.finder.find("Firefox Mobile", 0).await()?.total ?: 0 }
                total > 0
            }
            val adguardMobile = runBlocking(Dispatchers.Main) { shell.dashboardSession!!.geckoSession.finder.find("adguard-mobile", 0).await()?.total ?: 0 }
            evidence("S7 uBlock Origin support page: 'Firefox Mobile' found $total time(s), the auto-selected list 'adguard-mobile' (ua: mobile) $adguardMobile time(s)")
            assertTrue("AdGuard Mobile Ads list selected", adguardMobile > 0)
        } finally {
            DevOverrides.dashboardPage = null
        }
        pressBack()
    }

    @Test
    fun strictModeCancelsTheLoggingBeaconsAndIsOffByDefaultAndDuringCaptures() {
        awaitHome()
        val describe = runBlocking(Dispatchers.Main) { shell.engine.bridge.request("strict.describe") }
        assertFalse("off by default", describe.getBoolean("enabled"))
        fun beaconRound(): Map<String, Int> {
            val before = (ShellPages.LOGGING_PATHS + ShellPages.CONTROL_LOGGING_PATH).associateWith { requests(it) }
            val n = logs("beacons").size
            instrumentation.runOnMainSync { shell.session.load(server.url("/shell/beacons.html")) }
            waitFor("beacons sent") { logs("beacons").size == n + 1 }
            Thread.sleep(1_500) // sendBeacon is fire and forget
            return before.mapValues { (p, b) -> requests(p) - b }
        }
        val off = beaconRound()
        for (p in ShellPages.LOGGING_PATHS) assertEquals("strict off: $p reached the server", 2, off[p])

        compose.onNodeWithTag("shell-menu").performClick()
        compose.onNodeWithTag("menu-settings").performClick()
        compose.onNodeWithTag("setting-strict").performClick()
        waitFor("strict on") { shell.settings.strict.value }
        Thread.sleep(500)
        pressBack()
        val on = beaconRound()
        for (p in ShellPages.LOGGING_PATHS) assertEquals("strict on: $p cancelled", 0, on[p])
        assertEquals("control endpoint not touched", 2, on[ShellPages.CONTROL_LOGGING_PATH])
        evidence("S9 strict off: $off; strict on: $on (fetch and sendBeacon each); page saw ${logs("beacons").last()}")

        lateinit var recorder: io.github.chabiroael.twinbook.engine.capture.CaptureRecorder
        instrumentation.runOnMainSync { recorder = AppEngine.recorder(instrumentation.targetContext) }
        runBlocking(Dispatchers.Main) { recorder.start(listOf("mock")) }
        try {
            val during = runBlocking(Dispatchers.Main) { shell.engine.bridge.request("strict.describe") }
            assertFalse("inactive during a capture: $during", during.getBoolean("active"))
            val capturing = beaconRound()
            for (p in ShellPages.LOGGING_PATHS) assertEquals("capture running: $p reached the server", 2, capturing[p])
            evidence("S9 during a capture strict mode is inactive ($during): $capturing")
        } finally {
            runBlocking(Dispatchers.Main) { recorder.discard() }
        }
        val after = runBlocking(Dispatchers.Main) { shell.engine.bridge.request("strict.describe") }
        assertTrue("active again after the capture: $after", after.getBoolean("active"))
    }
}
