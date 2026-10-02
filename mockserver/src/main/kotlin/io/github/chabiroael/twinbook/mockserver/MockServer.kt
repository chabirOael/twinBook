package io.github.chabiroael.twinbook.mockserver

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * Hermetic HTTP/1.1 server bound to loopback that imitates the shape of the site's traffic.
 * It runs inside the instrumented test process on the device (and in JVM tests), so tests
 * need no host-side process. Every request is recorded; see [requests].
 *
 * Endpoints (see docs/ENGINE.md for details):
 * - `POST /api/graphql/?scenario=<name>&run=<id>&transport=<t>`: streamed NDJSON, see [Scenarios]
 * - `GET /test.html?...`: the test page (XHR and fetch readers, integrity checks)
 * - `GET /document?run=<id>`: HTML with JSON islands containing ad edges
 * - `GET /session/set-cookies?prefix=<p>&value=<v>[&maxAge=<s>]`: sets one cookie of each kind
 * - `GET|POST /session/echo[?pad=<n>]`: echoes the request (headers, cookies, body) as JSON
 * - `GET /anchor`: static page with no scripts, the replay anchor
 * - `GET /blank.html`: empty same-origin page for iframes
 * - `POST /report?run=<id>`: the page posts its results here; see [awaitReport]
 * - `POST /log?run=<id>&field=<f>`: pages log values here; see [logs]
 * - capture pages (secrets, Bloks-shaped fetch, login form, navigation, bulk): see [CapturePages]
 */
class MockServer : AutoCloseable {
    private val server = ServerSocket()
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "mockserver").apply { isDaemon = true }
    }
    private val recorded = CopyOnWriteArrayList<RecordedRequest>()
    private val reports = ConcurrentHashMap<String, CompletableFuture<String>>()
    private val traces = CopyOnWriteArrayList<StreamTrace>()

    @Volatile
    private var running = false

    /** Port the server listens on (loopback only). Valid after [start]. */
    val port: Int get() = server.localPort

    /** `http://127.0.0.1:<port>`: the "site origin" of every mock page. */
    val origin: String get() = "http://127.0.0.1:$port"

    fun start(): MockServer {
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        running = true
        pool.execute {
            while (running) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                pool.execute { serve(socket) }
            }
        }
        return this
    }

    override fun close() {
        running = false
        server.close()
        pool.shutdownNow()
    }

    /** Every request received so far, oldest first. */
    val requests: List<RecordedRequest> get() = recorded.toList()

    /** One entry per streamed GraphQL response, with the time each chunk was flushed. */
    val streamTraces: List<StreamTrace> get() = traces.toList()

    fun requestsTo(path: String): List<RecordedRequest> = recorded.filter { it.path == path }

    /** Waits for the page to post its report for [run] and returns the raw JSON. */
    fun awaitReport(run: String, timeoutMs: Long = 30_000): String =
        reports.computeIfAbsent(run) { CompletableFuture() }.get(timeoutMs, TimeUnit.MILLISECONDS)

    fun url(pathAndQuery: String): String = origin + pathAndQuery

    private fun serve(socket: Socket) {
        socket.use { s ->
            s.tcpNoDelay = true
            try {
                val input = BufferedInputStream(s.getInputStream())
                val request = HttpIo.readRequest(input) ?: return
                recorded += request
                val out = ResponseWriter(BufferedOutputStream(s.getOutputStream(), 64 * 1024))
                try {
                    route(request, out)
                } finally {
                    request.responseStatus = out.status
                    request.responseLength = out.contentLength
                    request.responseSha256 = out.contentSha256()
                }
            } catch (_: IOException) {
                // Peer went away; nothing to do.
            }
        }
    }

    private fun route(request: RecordedRequest, out: ResponseWriter) {
        when (request.path) {
            "/api/graphql/" -> graphql(request, out)
            "/test.html" -> out.sendText(200, HTML, resource("test-page.html"))
            "/static/test-page.js" -> out.sendText(200, "text/javascript; charset=utf-8", resource("test-page.js"))
            "/document" -> out.sendText(200, HTML, MockPages.document())
            "/anchor" -> out.sendText(200, HTML, MockPages.ANCHOR)
            "/blank.html" -> out.sendText(200, HTML, MockPages.BLANK)
            "/session/set-cookies" -> setCookies(request, out)
            "/session/echo" -> out.sendText(200, JSON_TYPE, echo(request), listOf("Access-Control-Allow-Origin" to "*"))
            "/log" -> {
                val run = request.query["run"].orEmpty()
                logs.computeIfAbsent(run) { CopyOnWriteArrayList() } += (request.query["field"].orEmpty() to request.body.toString(Charsets.UTF_8))
                out.send(204, "text/plain", ByteArray(0))
            }
            "/report" -> {
                val run = request.query["run"].orEmpty()
                reports.computeIfAbsent(run) { CompletableFuture() }.complete(request.body.toString(Charsets.UTF_8))
                out.send(204, "text/plain", ByteArray(0))
            }
            else -> if (!CapturePages.route(request, out)) out.sendText(404, "text/plain", "not found")
        }
    }

    private val logs = ConcurrentHashMap<String, CopyOnWriteArrayList<Pair<String, String>>>()

    /** What pages posted to `/log?run=<run>&field=<f>` (field to body), in arrival order. */
    fun logs(run: String): List<Pair<String, String>> = logs[run]?.toList().orEmpty()

    /** The last value logged for [field] in [run], if any. */
    fun lastLog(run: String, field: String): String? = logs(run).lastOrNull { it.first == field }?.second

    private fun graphql(request: RecordedRequest, out: ResponseWriter) {
        val scenario = Scenarios.find(request.query["scenario"].orEmpty())
        if (scenario == null) {
            out.sendText(400, "text/plain", "unknown scenario")
            return
        }
        val headers = if (scenario.gzip) listOf("Content-Encoding" to "gzip") else emptyList()
        val chunked = out.startChunked(200, JSON_TYPE, headers)
        val trace = StreamTrace(
            scenario = scenario.name,
            run = request.query["run"].orEmpty(),
            transport = request.query["transport"].orEmpty(),
            startedAtMillis = System.currentTimeMillis(),
        )
        traces += trace
        val sink = if (scenario.gzip) GZIPOutputStream(chunked, true) else chunked
        scenario.chunks().forEachIndexed { i, chunk ->
            if (i > 0 && scenario.delayMs > 0) Thread.sleep(scenario.delayMs)
            out.noteContent(chunk)
            sink.write(chunk)
            sink.flush()
            trace.chunkSentAtMillis += System.currentTimeMillis()
        }
        if (sink is GZIPOutputStream) sink.finish()
        chunked.close()
        trace.finishedAtMillis = System.currentTimeMillis()
    }

    private fun setCookies(request: RecordedRequest, out: ResponseWriter) {
        val prefix = request.query["prefix"] ?: "c"
        val value = request.query["value"] ?: "1"
        val maxAge = request.query["maxAge"]?.let { "; Max-Age=$it" }.orEmpty()
        val cookies = CookieKind.entries.map { kind ->
            "Set-Cookie" to "${kind.cookieName(prefix)}=$value; Path=/${kind.attributes}$maxAge"
        }
        out.sendText(200, HTML, MockPages.page("cookies set", "<p id=\"msg\">cookies set: $prefix</p>"), cookies)
    }

    private fun echo(request: RecordedRequest): String {
        val pad = request.query["pad"]?.toIntOrNull()
        val map = linkedMapOf<String, Any?>(
            "method" to request.method,
            "path" to request.path,
            "query" to request.query,
            "headers" to request.headers.map { listOf(it.first, it.second) },
            "cookies" to request.cookies,
            "body" to request.body.toString(Charsets.UTF_8),
            "receivedAt" to request.receivedAtMillis,
        )
        if (pad != null) map["payload"] = payload(pad)
        return Json.write(map)
    }

    private fun resource(name: String): String =
        MockServer::class.java.getResourceAsStream("/io/github/chabiroael/twinbook/mockserver/www/$name")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("missing resource $name")

    companion object {
        const val HTML = "text/html; charset=utf-8"
        const val JSON_TYPE = "application/json; charset=utf-8"

        /** Deterministic text with ASCII and multi-byte characters: [units] repeats of one unit. */
        fun payload(units: Int): String = "twinBook é 😀 مرحبا 中文 | ".repeat(units)
    }
}

/** The cookie kinds `/session/set-cookies` sets. Cookie name is `<prefix>_<suffix>`. */
enum class CookieKind(val suffix: String, val attributes: String) {
    PLAIN("plain", ""),
    HTTP_ONLY("httponly", "; HttpOnly"),
    LAX("lax", "; SameSite=Lax"),
    STRICT("strict", "; SameSite=Strict"),
    NONE_SECURE("none", "; SameSite=None; Secure"),
    ;

    fun cookieName(prefix: String) = "${prefix}_$suffix"
}

/** When each chunk of one streamed response left the server. */
class StreamTrace(
    val scenario: String,
    val run: String,
    val transport: String,
    val startedAtMillis: Long,
) {
    val chunkSentAtMillis: MutableList<Long> = CopyOnWriteArrayList()

    @Volatile
    var finishedAtMillis: Long = 0
}
