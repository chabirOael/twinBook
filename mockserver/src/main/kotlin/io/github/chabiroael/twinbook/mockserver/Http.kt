package io.github.chabiroael.twinbook.mockserver

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder

/** One HTTP request as the mock server received it. */
class RecordedRequest(
    val method: String,
    /** Path without the query string. */
    val path: String,
    val rawQuery: String,
    /** Headers in arrival order, names as sent. */
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
    val receivedAtMillis: Long,
) {
    /** Status of the response the server sent, once sent (0 before). */
    @Volatile
    var responseStatus: Int = 0
        internal set

    /** SHA-256 (hex) of the response body content as served, before any gzip coding. */
    @Volatile
    var responseSha256: String? = null
        internal set

    @Volatile
    var responseLength: Long = 0
        internal set

    val query: Map<String, String> by lazy { parseForm(rawQuery) }

    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    /** Cookies from the Cookie header, in order. */
    val cookies: Map<String, String> by lazy {
        val result = linkedMapOf<String, String>()
        header("Cookie")?.split(';')?.forEach { part ->
            val eq = part.indexOf('=')
            if (eq > 0) result[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
        }
        result
    }

    /** Form fields of an application/x-www-form-urlencoded body, in order (first value wins). */
    val form: Map<String, String> by lazy { parseForm(body.toString(Charsets.UTF_8)) }

    /** All field names of the form body, in order, duplicates kept. */
    val formFieldNames: List<String> by lazy {
        body.toString(Charsets.UTF_8).split('&').filter { it.isNotEmpty() }.map { decode(it.substringBefore('=')) }
    }

    override fun toString(): String = "$method $path${if (rawQuery.isEmpty()) "" else "?$rawQuery"}"

    companion object {
        fun parseForm(text: String): Map<String, String> {
            val result = linkedMapOf<String, String>()
            text.split('&').filter { it.isNotEmpty() }.forEach { pair ->
                val name = decode(pair.substringBefore('='))
                if (name !in result) result[name] = decode(pair.substringAfter('=', ""))
            }
            return result
        }

        private fun decode(s: String): String = URLDecoder.decode(s, Charsets.UTF_8.name())
    }
}

internal object HttpIo {
    private const val MAX_HEADER_BYTES = 64 * 1024

    /** Reads one request, or returns null if the peer closed the connection first. */
    fun readRequest(input: BufferedInputStream): RecordedRequest? {
        val head = readHead(input) ?: return null
        val lines = head.split("\r\n")
        val requestLine = lines.first().split(' ')
        if (requestLine.size < 3) throw IOException("bad request line: ${lines.first()}")
        val headers = lines.drop(1).filter { it.isNotEmpty() }.map {
            val colon = it.indexOf(':')
            if (colon <= 0) throw IOException("bad header line: $it")
            it.substring(0, colon).trim() to it.substring(colon + 1).trim()
        }
        val target = requestLine[1]
        val length = headers.firstOrNull { it.first.equals("Content-Length", true) }?.second?.toInt() ?: 0
        val chunked = headers.any { it.first.equals("Transfer-Encoding", true) && it.second.contains("chunked", true) }
        val body = if (chunked) readChunkedBody(input) else readFully(input, length)
        return RecordedRequest(
            method = requestLine[0],
            path = target.substringBefore('?'),
            rawQuery = target.substringAfter('?', ""),
            headers = headers,
            body = body,
            receivedAtMillis = System.currentTimeMillis(),
        )
    }

    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (buf.size() == 0) return null
                throw IOException("connection closed inside request head")
            }
            buf.write(b)
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
            if (matched == 4) break
            if (buf.size() > MAX_HEADER_BYTES) throw IOException("request head too large")
        }
        val bytes = buf.toByteArray()
        return String(bytes, 0, bytes.size - 4, Charsets.ISO_8859_1)
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(out, read, length - read)
            if (n < 0) throw IOException("connection closed inside body")
            read += n
        }
        return out
    }

    private fun readChunkedBody(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input)
            val size = sizeLine.substringBefore(';').trim().toInt(16)
            if (size == 0) {
                while (readLine(input).isNotEmpty()) Unit
                return out.toByteArray()
            }
            out.write(readFully(input, size))
            readLine(input)
        }
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("connection closed")
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }
}

/** Writes HTTP/1.1 responses. Every response closes the connection. */
class ResponseWriter internal constructor(private val out: OutputStream) {
    private var started = false
    private val digest = java.security.MessageDigest.getInstance("SHA-256")
    internal var status = 0
        private set
    internal var contentLength = 0L
        private set

    /** Adds body content (before any content coding) to the response's hash. */
    fun noteContent(bytes: ByteArray) {
        digest.update(bytes)
        contentLength += bytes.size
    }

    internal fun contentSha256(): String = digest.digest().joinToString("") { "%02x".format(it) }

    /** A complete response with a fixed body. */
    fun send(status: Int, contentType: String, body: ByteArray, headers: List<Pair<String, String>> = emptyList()) {
        writeHead(status, listOf("Content-Type" to contentType, "Content-Length" to body.size.toString()) + headers)
        noteContent(body)
        out.write(body)
        out.flush()
    }

    fun sendText(status: Int, contentType: String, text: String, headers: List<Pair<String, String>> = emptyList()) =
        send(status, contentType, text.toByteArray(Charsets.UTF_8), headers)

    /** Starts a chunked response. Each [ChunkedBody.write] is flushed to the socket as one HTTP chunk. */
    fun startChunked(status: Int, contentType: String, headers: List<Pair<String, String>> = emptyList()): ChunkedBody {
        writeHead(status, listOf("Content-Type" to contentType, "Transfer-Encoding" to "chunked") + headers)
        out.flush()
        return ChunkedBody(out)
    }

    private fun writeHead(status: Int, headers: List<Pair<String, String>>) {
        check(!started) { "response already started" }
        started = true
        this.status = status
        val sb = StringBuilder("HTTP/1.1 $status ${reason(status)}\r\n")
        for ((name, value) in headers + listOf("Connection" to "close", "Cache-Control" to "no-store")) {
            sb.append(name).append(": ").append(value).append("\r\n")
        }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "Status"
    }
}

/** HTTP chunked transfer coding over the socket. close() writes the terminating chunk. */
class ChunkedBody internal constructor(private val out: OutputStream) : OutputStream() {
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        out.write("${Integer.toHexString(len)}\r\n".toByteArray(Charsets.ISO_8859_1))
        out.write(b, off, len)
        out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    override fun flush() = out.flush()

    override fun close() {
        out.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }
}
