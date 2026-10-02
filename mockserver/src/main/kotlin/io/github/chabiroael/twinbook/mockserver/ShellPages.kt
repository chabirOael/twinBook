package io.github.chabiroael.twinbook.mockserver

import java.net.URLEncoder

/**
 * Mock pages for the web shell (M3a). Each takes `run=<id>` and logs to `/log?run=<id>`.
 *
 * - `GET /shell/home.html`: the shell's start page. Internal links (feed, long page, form, dark
 *   page, ads page, beacons page), an outbound link through the redirect page with tracking
 *   parameters, a direct outbound link, an outbound link in a new window, `tel:`, `mailto:` and
 *   `geo:` links. Reports element positions (`layout`) and `loaded`.
 * - `GET /shell/feed.html`, `/shell/long.html` (200 paragraphs; reports `scroll` positions),
 *   `/shell/form.html` (a comment box; every input logged), `/shell/dark.html` (reports which
 *   colour scheme it got, `scheme`, on load and on every change).
 * - `GET /shell/ads.html`: an element that a default uBlock Origin cosmetic filter hides
 *   (EasyList `###ad-banner-top` and `##.abovead`), a control element, and an image that a default
 *   list blocks (EasyPrivacy `/__utm.gif`). Generic cosmetic filters apply only under
 *   [NAMED_HOST] (EasyList excepts 127.0.0.1 and localhost with `$generichide`). Reports `cosmetic` (which elements are displayed)
 *   every 250 ms until 4 s after load.
 * - `GET /shell/beacons.html`: sends beacons to the two endpoints shaped like the mobile site's
 *   logging beacons and to a control endpoint, then reports `beacons`.
 * - `POST|GET /ajax/weblite_load_logging/`, `/ajax/weblite_resources_timing_logging/`,
 *   `/ajax/control_logging/`: 204.
 * - `GET /__utm.gif`: a 1x1 GIF (it must never be requested while ad hiding is on).
 * - `GET /l.php?u=<target>&h=<hash>`: the outbound redirect page. The shell must never request
 *   it; if it is requested anyway, it answers with a page that says so (and no redirect).
 */
object ShellPages {
    /** The outbound target the home page links to through the redirect page, tracking parameters included. */
    const val OUTBOUND_TARGET = "https://example.com/article?id=7&fbclid=IwAR0mock&utm_source=facebook&utm_medium=social"

    /** [OUTBOUND_TARGET] as the shell must hand it to the browser. */
    const val OUTBOUND_CLEAN = "https://example.com/article?id=7"

    const val DIRECT_TARGET = "https://example.org/direct?ref=mock&utm_campaign=x"
    const val DIRECT_CLEAN = "https://example.org/direct?ref=mock"
    const val NEW_WINDOW_TARGET = "https://example.net/new-window?gclid=abc&k=v"
    const val NEW_WINDOW_CLEAN = "https://example.net/new-window?k=v"

    val LOGGING_PATHS = listOf("/ajax/weblite_load_logging/", "/ajax/weblite_resources_timing_logging/")
    const val CONTROL_LOGGING_PATH = "/ajax/control_logging/"

    /**
     * Name under which the app's debug build reaches the mock on the loopback interface. EasyList
     * turns generic cosmetic filters off for 127.0.0.1 and localhost, so element hiding is tested
     * under this name.
     */
    const val NAMED_HOST = "mock.twinbook.test"

    private val GIF = byteArrayOf(
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00,
        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x21, 0xF9.toByte(), 0x04, 0x01, 0x00, 0x00, 0x00, 0x00, 0x2C, 0x00, 0x00, 0x00, 0x00,
        0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3B,
    )

    fun route(request: RecordedRequest, out: ResponseWriter): Boolean {
        when (request.path) {
            "/shell/home.html" -> out.sendText(200, MockServer.HTML, HOME)
            "/shell/feed.html" -> out.sendText(200, MockServer.HTML, page("mock feed", "<h1 id=\"title\">Feed</h1>" + (1..20).joinToString("") { "<p class=\"post\">Post $it</p>" } + "<a class=\"big\" id=\"home\" href=\"/shell/home.html\">home</a>", "layout([\"home\"]); log(\"loaded\", \"feed\");"))
            "/shell/long.html" -> out.sendText(200, MockServer.HTML, LONG)
            "/shell/form.html" -> out.sendText(200, MockServer.HTML, FORM)
            "/shell/dark.html" -> out.sendText(200, MockServer.HTML, DARK)
            "/shell/ads.html" -> out.sendText(200, MockServer.HTML, ADS)
            "/shell/beacons.html" -> out.sendText(200, MockServer.HTML, BEACONS)
            "/ajax/weblite_load_logging/", "/ajax/weblite_resources_timing_logging/", CONTROL_LOGGING_PATH -> out.send(204, "text/plain", ByteArray(0))
            "/__utm.gif" -> out.send(200, "image/gif", GIF)
            "/l.php" -> out.sendText(200, MockServer.HTML, page("redirect page", "<p id=\"msg\">the redirect page was loaded</p>", "log(\"redirect-page\", \"loaded\");"))
            else -> return false
        }
        return true
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private const val STYLE = """<meta name="viewport" content="width=device-width, initial-scale=1"><style>
:root { color-scheme: light dark; }
body { font: 18px sans-serif; margin: 0; padding: 8px; }
a.big, button, textarea { display: block; box-sizing: border-box; width: 100%; min-height: 48px; margin: 0 0 6px; font-size: 18px; }
a.big { line-height: 48px; background: #eef; color: #003; text-align: center; text-decoration: none; }
.box { height: 60px; margin: 6px 0; background: #fcc; }
</style>"""

    private const val LOG_SCRIPT = """
var run = new URLSearchParams(location.search).get("run") || "";
function log(field, value) {
  var xhr = new XMLHttpRequest();
  xhr.open("POST", "/log?run=" + encodeURIComponent(run) + "&field=" + encodeURIComponent(field));
  xhr.send(String(value));
}
var layoutIds = null;
// Reported again whenever the viewport changes (a page can load before its view has a size).
window.addEventListener("resize", function () { if (layoutIds) layout(layoutIds); });
function layout(ids) {
  layoutIds = ids;
  var r = { dpr: window.devicePixelRatio, vw: window.innerWidth, vh: window.innerHeight, els: {} };
  ids.forEach(function (id) {
    var b = document.getElementById(id).getBoundingClientRect();
    r.els[id] = [b.left, b.top, b.width, b.height];
  });
  log("layout", JSON.stringify(r));
}
function withRun(a) { a.href = a.getAttribute("href") + (a.getAttribute("href").indexOf("?") < 0 ? "?" : "&") + "run=" + encodeURIComponent(run); }
"""

    private fun page(title: String, body: String, onLoad: String): String =
        """<!DOCTYPE html><html><head><meta charset="utf-8"><title>$title</title>$STYLE</head><body>$body<script>$LOG_SCRIPT
document.querySelectorAll("a.internal").forEach(withRun);
window.addEventListener("load", function () { $onLoad });
</script></body></html>"""

    val HOME: String = page(
        "mock home",
        """<h1 id="title">Home</h1>
<a class="big internal" id="feed" href="/shell/feed.html">feed</a>
<a class="big" id="outbound" href="/l.php?u=${enc(OUTBOUND_TARGET)}&amp;h=AT0mockHash">outbound through the redirect page</a>
<a class="big" id="direct" href="$DIRECT_TARGET">outbound, direct</a>
<a class="big" id="newwin" href="$NEW_WINDOW_TARGET" target="_blank">outbound, new window</a>
<a class="big" id="tel" href="tel:+15550100">phone</a>
<a class="big" id="mailto" href="mailto:someone@example.com">mail</a>
<a class="big" id="geo" href="geo:25.28,51.53">map</a>
<a class="big internal" id="long" href="/shell/long.html">long page</a>
<a class="big internal" id="form" href="/shell/form.html">form</a>
<a class="big internal" id="dark" href="/shell/dark.html">dark</a>
<a class="big internal" id="ads" href="/shell/ads.html">ads</a>
<a class="big internal" id="beacons" href="/shell/beacons.html">beacons</a>
<a class="big" id="adsnamed" href="/shell/ads.html">ads under the mock's host name</a>""",
        """document.getElementById("adsnamed").href = "http://$NAMED_HOST:" + location.port + "/shell/ads.html";
layout(["feed", "outbound", "direct", "newwin", "tel", "mailto", "geo", "long", "form", "dark", "ads", "beacons", "adsnamed"]); log("loaded", "home");""",
    )

    val LONG: String = page(
        "mock long page",
        """<h1 id="title">Long page</h1>""" + (1..200).joinToString("") { "<p>Paragraph $it of a long page that scrolls.</p>" },
        """log("loaded", "long"); var last = -1; setInterval(function () { var y = Math.round(window.scrollY); if (y !== last) { last = y; log("scroll", y); } }, 250);""",
    )

    val FORM: String = page(
        "mock form",
        """<h1 id="title">Form</h1><form id="f" onsubmit="log('submit', 'blocked'); return false;"><textarea id="comment" placeholder="Write a comment"></textarea><button id="send" type="submit">Send</button></form>""",
        """layout(["comment", "send"]); log("loaded", "form");
document.getElementById("comment").addEventListener("input", function (e) { log("comment", e.target.value); });
document.getElementById("comment").addEventListener("focus", function () { log("focus", "comment"); });""",
    )

    val DARK: String = page(
        "mock dark scheme",
        """<h1 id="title">Colour scheme</h1><p id="scheme">?</p><style>@media (prefers-color-scheme: dark) { body { background: #111; color: #eee; } }</style>""",
        """var mq = window.matchMedia("(prefers-color-scheme: dark)");
function report() { var s = mq.matches ? "dark" : "light"; document.getElementById("scheme").textContent = s; log("scheme", s); }
report(); mq.addEventListener("change", report); log("loaded", "dark");""",
    )

    val ADS: String = page(
        "mock ads",
        """<h1 id="title">Ads</h1>
<div class="box" id="ad-banner-top">listed by id (EasyList ###ad-banner-top)</div>
<div class="box abovead" id="listed-class">listed by class (EasyList ##.abovead)</div>
<div class="box" id="content-ok">ordinary content</div>
<img id="pixel" src="/__utm.gif?mock=1" width="1" height="1" alt="">""",
        """var started = Date.now(), last = "";
function shown(id) { var e = document.getElementById(id); var cs = getComputedStyle(e); return cs.display !== "none" && cs.visibility !== "hidden" && e.offsetHeight > 0; }
function tick() {
  var r = JSON.stringify({ banner: shown("ad-banner-top"), byClass: shown("listed-class"), content: shown("content-ok"), pixel: document.getElementById("pixel").complete && document.getElementById("pixel").naturalWidth > 0 });
  if (r !== last) { last = r; log("cosmetic", r); }
  if (Date.now() - started < 4000) setTimeout(tick, 250); else log("cosmetic-done", r);
}
tick(); log("loaded", "ads");""",
    )

    val BEACONS: String = page(
        "mock beacons",
        """<h1 id="title">Beacons</h1>""",
        """var results = {};
var paths = ["/ajax/weblite_load_logging/", "/ajax/weblite_resources_timing_logging/", "/ajax/control_logging/"];
Promise.all(paths.map(function (p) {
  return fetch(p + "?run=" + encodeURIComponent(run), { method: "POST", body: "x=1" }).then(function (r) { results[p] = "status " + r.status; }, function (e) { results[p] = "failed"; });
})).then(function () {
  paths.forEach(function (p) { navigator.sendBeacon(p + "?run=" + encodeURIComponent(run) + "&via=beacon", "y=2"); });
  log("beacons", JSON.stringify(results));
});
log("loaded", "beacons");""",
    )
}
