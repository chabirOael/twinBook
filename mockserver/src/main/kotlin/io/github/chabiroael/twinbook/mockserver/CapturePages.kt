package io.github.chabiroael.twinbook.mockserver

import java.security.MessageDigest

/**
 * Secrets the secrets page plants for one run, the way the real site does. All values are
 * derived from the run id, so a test can scan a capture for exactly these values.
 */
class MockSecrets(run: String) {
    private val h = MessageDigest.getInstance("SHA-256").digest("twinbook-secrets:$run".toByteArray()).joinToString("") { "%02x".format(it) }

    /** Anti-forgery token: unkeyed in the HTML first, later sent as the fb_dtsg form field. */
    val dtsg = "MOCKDTSG${h.substring(0, 20)}:17:${h.substring(20, 30).filter { it.isDigit() }.padEnd(6, '7')}"

    /** lsd token, sent as a form field. */
    val lsd = "MOCKLSD${h.substring(30, 44)}"

    /** Short checksum field: only layer 1 can redact it (shorter than the taint minimum). */
    val jazoest = "2" + h.substring(44, 64).filter { it.isDigit() }.padEnd(4, '1').take(4)

    /** Value of the HttpOnly session cookie; echoed later inside a JSON body. */
    val session = "MOCKSESS${h.substring(0, 24).reversed()}"

    /** Value of the c_user cookie (a user id); also echoed in a JSON body and unkeyed in the HTML. */
    val userId = "1000" + h.filter { it.isDigit() }.padEnd(11, '3').take(11)

    /** Every planted secret long enough for layer 2, by label. */
    val all: Map<String, String> get() = mapOf("field:fb_dtsg" to dtsg, "field:lsd" to lsd, "cookie:mock_sess" to session, "cookie:c_user" to userId)
}

/**
 * Mock pages for the capture tooling (M2a). Each takes `run=<id>`.
 *
 * - `GET /secrets/page`: sets cookies with attributes; the HTML holds the dtsg token and the user
 *   id unkeyed; its script then posts the token as `fb_dtsg` (with `lsd`, `jazoest`), fetches
 *   `/secrets/json` (which echoes the cookie values in JSON), posts to the Bloks-shaped
 *   endpoint, and reports to `/report`.
 * - `POST /secrets/form`, `GET /secrets/json`: the requests above.
 * - `GET|POST /async/wbloks/fetch/`: a single JSON document behind `for (;;);`, served as
 *   `application/x-javascript`, like the mobile site's fetch endpoint.
 * - `GET /login.html`: a login-like form; every input event is logged to `/log`.
 * - `GET /nav.html`: custom-scheme and intent links, a target=_blank link, window.open,
 *   geolocation and notification permission requests, alert, confirm, prompt; results go to `/log`.
 * - `GET /bulk?i=<n>&size=<bytes>`: a deterministic JSON body of exactly `size` bytes.
 * - `GET /bulk.html?n=<count>&size=<bytes>&big=<bytes>&par=<k>`: fetches `n` bulk responses
 *   (the first one `big` bytes), `par` at a time, then reports.
 */
object CapturePages {
    const val JS_TYPE = "application/x-javascript; charset=utf-8"

    fun route(request: RecordedRequest, out: ResponseWriter): Boolean {
        val run = request.query["run"].orEmpty()
        when (request.path) {
            "/secrets/page" -> {
                val s = MockSecrets(run)
                out.sendText(
                    200,
                    MockServer.HTML,
                    secretsPage(run, s),
                    listOf(
                        "Set-Cookie" to "mock_sess=${s.session}; Path=/; HttpOnly; SameSite=Lax; Max-Age=3600",
                        "Set-Cookie" to "c_user=${s.userId}; Path=/; Max-Age=3600; SameSite=None; Secure",
                    ),
                )
            }
            "/secrets/form" -> out.sendText(200, MockServer.JSON_TYPE, """{"ok":true,"fields":${request.formFieldNames.size}}""")
            "/secrets/json" -> {
                val s = MockSecrets(run)
                out.sendText(200, MockServer.JSON_TYPE, """{"viewer":{"id":"${s.userId}","sessionEcho":"${s.session}","note":"cookie values echoed in a later JSON body"}}""")
            }
            "/async/wbloks/fetch/" -> out.sendText(200, JS_TYPE, bloksDocument(run))
            "/login.html" -> out.sendText(200, MockServer.HTML, LOGIN_PAGE)
            "/login/submit" -> out.sendText(200, MockServer.HTML, MockPages.page("submitted", "<p id=\"msg\">submitted</p>"))
            "/nav.html" -> out.sendText(200, MockServer.HTML, NAV_PAGE)
            "/bulk" -> {
                val i = request.query["i"]?.toIntOrNull() ?: 0
                val size = request.query["size"]?.toIntOrNull() ?: 1000
                out.send(200, MockServer.JSON_TYPE, bulkBody(i, size))
            }
            "/bulk.html" -> out.sendText(200, MockServer.HTML, BULK_PAGE)
            else -> return false
        }
        return true
    }

    /** The Bloks-shaped single document, guard included. */
    fun bloksDocument(run: String): String =
        "for (;;);" + Json.write(
            linkedMapOf(
                "__ar" to 1,
                "payload" to linkedMapOf(
                    "layout" to linkedMapOf("bloks_payload" to linkedMapOf("tree" to linkedMapOf("bk.components.Flexbox" to linkedMapOf("children" to listOf("a", "b"))))),
                    "ad_id" to "mock-ad-1",
                    "run" to run,
                ),
                "lid" to "7300000000000000001",
            ),
        )

    /** Deterministic body of exactly [size] bytes (ASCII JSON). */
    fun bulkBody(i: Int, size: Int): ByteArray {
        val head = """{"i":$i,"pad":""""
        val tail = "\"}"
        val padLength = (size - head.length - tail.length).coerceAtLeast(0)
        val sb = StringBuilder(size)
        sb.append(head)
        val unit = "bulk-$i-0123456789abcdefghijklmnopqrstuvwxyz|"
        while (sb.length < head.length + padLength) sb.append(unit)
        sb.setLength(head.length + padLength)
        sb.append(tail)
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    private fun secretsPage(run: String, s: MockSecrets): String = MockPages.page(
        "mock secrets",
        "<p id=\"msg\">mock secrets page</p>" +
            // Unkeyed: layer 1 cannot know these values yet.
            "<script>window.__boot = [\"${s.dtsg}\", ${s.userId}];</script>" +
            "<script>" + SECRETS_SCRIPT.replace("__LSD__", s.lsd).replace("__JAZOEST__", s.jazoest).replace("__RUN__", run) + "</script>",
    )

    private const val SECRETS_SCRIPT = """
(function () {
  var run = "__RUN__";
  var token = window.__boot[0];
  function post(url, body, type) {
    return fetch(url, { method: "POST", headers: { "Content-Type": type }, body: body });
  }
  var form = "fb_dtsg=" + encodeURIComponent(token) + "&lsd=__LSD__&jazoest=__JAZOEST__&__rev=1007000000&__req=1&q=hello";
  post("/secrets/form?run=" + run, form, "application/x-www-form-urlencoded")
    .then(function () { return fetch("/secrets/json?run=" + run + "&fb_dtsg_ag=" + encodeURIComponent(token)); })
    .then(function (r) { return r.json(); })
    .then(function (json) {
      return new Promise(function (resolve) {
        var xhr = new XMLHttpRequest();
        xhr.open("POST", "/async/wbloks/fetch/?run=" + run);
        xhr.setRequestHeader("Content-Type", "application/x-www-form-urlencoded");
        xhr.onload = function () { resolve({ json: json, bloks: xhr.responseText }); };
        xhr.send("__a=1&fb_dtsg=" + encodeURIComponent(token) + "&lsd=__LSD__");
      });
    })
    .then(function (r) {
      var xhr = new XMLHttpRequest();
      xhr.open("POST", "/report?run=" + run);
      xhr.send(JSON.stringify({ run: run, done: true, echo: r.json.viewer.sessionEcho, bloksLength: r.bloks.length, bloksStart: r.bloks.slice(0, 9) }));
    });
})();
"""

    /** Shared by the pages that tests drive by tapping: reports element positions in CSS pixels. */
    private const val LOG_SCRIPT = """
var run = new URLSearchParams(location.search).get("run") || "";
function log(field, value) {
  var xhr = new XMLHttpRequest();
  xhr.open("POST", "/log?run=" + encodeURIComponent(run) + "&field=" + encodeURIComponent(field));
  xhr.send(String(value));
}
function layout(ids) {
  var r = { dpr: window.devicePixelRatio, vw: window.innerWidth, vh: window.innerHeight, els: {} };
  ids.forEach(function (id) {
    var b = document.getElementById(id).getBoundingClientRect();
    r.els[id] = [b.left, b.top, b.width, b.height];
  });
  log("layout", JSON.stringify(r));
}
"""

    private const val STYLE = """<meta name="viewport" content="width=device-width, initial-scale=1"><style>
body { font: 18px sans-serif; margin: 0; padding: 8px; }
input, button, a.big { display: block; box-sizing: border-box; width: 100%; height: 64px; margin: 0 0 10px; font-size: 20px; }
a.big { line-height: 64px; background: #eef; text-align: center; }
</style>"""

    val LOGIN_PAGE = """<!DOCTYPE html><html><head><meta charset="utf-8"><title>mock login</title>$STYLE</head><body>
<form id="form" method="post" action="/login/submit">
<input id="email" name="email" type="text" autocomplete="username" placeholder="email or phone">
<input id="pass" name="pass" type="password" autocomplete="current-password" placeholder="password">
<button id="go" type="submit">Log in</button>
</form>
<script>$LOG_SCRIPT
["email", "pass"].forEach(function (id) {
  document.getElementById(id).addEventListener("input", function (e) { log(id, e.target.value); });
  document.getElementById(id).addEventListener("focus", function () { log("focus", id); });
});
window.addEventListener("load", function () { layout(["email", "pass", "go"]); });
</script></body></html>"""

    val NAV_PAGE = """<!DOCTYPE html><html><head><meta charset="utf-8"><title>mock nav</title>$STYLE</head><body>
<a class="big" id="scheme" href="fb://profile/1234">custom scheme</a>
<a class="big" id="intent" href="intent://profile/1234#Intent;scheme=fb;package=com.facebook.katana;end">intent link</a>
<a class="big" id="newwin" href="/blank.html?newwin=1" target="_blank">new window link</a>
<button id="open">window.open</button>
<button id="geo">geolocation</button>
<button id="notify">notification</button>
<button id="alert">alert</button>
<button id="confirm">confirm</button>
<button id="prompt">prompt</button>
<script>$LOG_SCRIPT
document.getElementById("open").onclick = function () { window.open("/blank.html?opened=1"); };
document.getElementById("geo").onclick = function () {
  navigator.geolocation.getCurrentPosition(function () { log("geo", "granted"); }, function (e) { log("geo", "denied:" + e.code); });
};
document.getElementById("notify").onclick = function () {
  Notification.requestPermission().then(function (p) { log("notify", p); });
};
document.getElementById("alert").onclick = function () { alert("mock alert"); log("alert", "closed"); };
document.getElementById("confirm").onclick = function () { log("confirm", String(confirm("mock confirm"))); };
document.getElementById("prompt").onclick = function () { log("prompt", String(prompt("mock prompt", "default"))); };
window.addEventListener("load", function () { layout(["scheme", "intent", "newwin", "open", "geo", "notify", "alert", "confirm", "prompt"]); });
</script></body></html>"""

    val BULK_PAGE = """<!DOCTYPE html><html><head><meta charset="utf-8"><title>mock bulk</title></head><body><p id="msg">bulk</p>
<script>
(function () {
  var q = new URLSearchParams(location.search);
  var run = q.get("run") || "", n = +(q.get("n") || 200), size = +(q.get("size") || 2000), big = +(q.get("big") || 0), par = +(q.get("par") || 6);
  var next = 0, done = 0, bytes = 0, failed = 0, t0 = Date.now();
  function one() {
    if (next >= n) return Promise.resolve();
    var i = next++;
    return fetch("/bulk?run=" + run + "&i=" + i + "&size=" + (i === 0 && big > 0 ? big : size))
      .then(function (r) { return r.arrayBuffer(); })
      .then(function (b) { bytes += b.byteLength; done++; }, function () { failed++; })
      .then(one);
  }
  var workers = [];
  for (var k = 0; k < par; k++) workers.push(one());
  Promise.all(workers).then(function () {
    var xhr = new XMLHttpRequest();
    xhr.open("POST", "/report?run=" + run);
    xhr.send(JSON.stringify({ run: run, done: done, failed: failed, bytes: bytes, ms: Date.now() - t0 }));
  });
})();
</script></body></html>"""
}
