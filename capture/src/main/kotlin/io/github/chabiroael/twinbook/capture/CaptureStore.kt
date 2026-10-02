package io.github.chabiroael.twinbook.capture

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Capture sessions in a directory of app-private storage. Layout of one session (docs/CAPTURE.md):
 *
 * ```
 * <root>/<id>/
 *   OPEN               present while recording; removed by finalize
 *   events.ndjson      one JSON record per line, layer 1 applied by the extension
 *   bodies/<rid>.res   response bodies, layer 1 applied
 *   session.json       written by finalize: counters, taint summary (labels, never values)
 *   checksums.sha256   written by finalize, `sha256sum -c` format, every other file
 *   FINALIZED          written last, atomically: format and the checksum file's own hash
 * ```
 *
 * A session without FINALIZED is not finalized: it cannot be pulled, and [deleteUnfinalized]
 * removes it when the app starts.
 */
class CaptureStore(val root: File) {
    init {
        root.mkdirs()
    }

    fun dir(id: String): File {
        require(ID.matches(id)) { "bad session id" }
        return File(root, id)
    }

    fun isFinalized(id: String): Boolean = File(dir(id), FINALIZED).isFile

    /** Sessions on disk, oldest first. */
    fun list(): List<SessionInfo> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory && ID.matches(it.name) }
            .sortedBy { it.name }
            .map { d -> SessionInfo(d.name, File(d, FINALIZED).isFile, d.walkTopDown().filter { it.isFile }.sumOf { it.length() }) }

    /** Deletes every session that was not finalized. Call once at app start. Returns their ids. */
    fun deleteUnfinalized(): List<String> =
        list().filter { !it.finalized }.map { it.id }.onEach { dir(it).deleteRecursively() }

    fun delete(id: String) {
        dir(id).deleteRecursively()
    }

    /** Creates a new session directory and opens it for writing. */
    fun create(id: String, maxBytes: Long = DEFAULT_MAX_SESSION_BYTES): SessionWriter {
        val d = dir(id)
        check(!d.exists()) { "session $id exists" }
        File(d, "bodies").mkdirs()
        File(d, OPEN).writeText("recording\n")
        return SessionWriter(id, d, maxBytes)
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val OPEN = "OPEN"
        const val FINALIZED = "FINALIZED"
        const val EVENTS = "events.ndjson"
        const val SESSION = "session.json"
        const val CHECKSUMS = "checksums.sha256"
        const val DEFAULT_MAX_SESSION_BYTES = 1L shl 30
        val ID = Regex("[A-Za-z0-9_-]{1,64}")
        private val BODY_FILE = Regex("bodies/[A-Za-z0-9_-]{1,80}\\.res")

        fun isBodyFile(name: String): Boolean = BODY_FILE.matches(name)
    }
}

class SessionInfo(val id: String, val finalized: Boolean, val bytes: Long)

/** One item of a `capture.write` batch. */
sealed interface CaptureItem {
    /** A line of events.ndjson, without its newline. */
    class Line(val text: String) : CaptureItem

    /** Bytes appended to a body file; [last] closes it. */
    class Body(val file: String, val bytes: ByteArray, val last: Boolean) : CaptureItem
}

/** Live counters of a session being written. */
data class WriterCounters(
    val lines: Long = 0,
    val bodies: Long = 0,
    val bytes: Long = 0,
    val droppedBodyBytes: Long = 0,
    val errors: Long = 0,
    val batches: Long = 0,
    val duplicateBatches: Long = 0,
)

/**
 * Appends batches to a session in order. Not thread-safe: the app calls it from one coroutine
 * at a time (the extension sends one batch at a time).
 */
class SessionWriter internal constructor(val id: String, val dir: File, private val maxBytes: Long) {
    private val events: OutputStream = BufferedOutputStream(FileOutputStream(File(dir, CaptureStore.EVENTS), true), 64 * 1024)
    private val open = HashMap<String, OutputStream>()
    private var lastSeq = 0L
    private var closed = false

    var counters = WriterCounters()
        private set

    /**
     * Writes one batch unless its sequence number was already written (a re-sent batch whose
     * answer was lost). Returns false for such a duplicate.
     */
    fun append(seq: Long, items: List<CaptureItem>): Boolean {
        check(!closed) { "session $id is closed" }
        if (seq <= lastSeq) {
            counters = counters.copy(duplicateBatches = counters.duplicateBatches + 1)
            return false
        }
        var c = counters
        for (item in items) {
            when (item) {
                is CaptureItem.Line -> {
                    val bytes = item.text.toByteArray(Charsets.UTF_8)
                    events.write(bytes)
                    events.write('\n'.code)
                    c = c.copy(lines = c.lines + 1, bytes = c.bytes + bytes.size + 1)
                }
                is CaptureItem.Body -> {
                    if (!CaptureStore.isBodyFile(item.file)) {
                        c = c.copy(errors = c.errors + 1)
                        continue
                    }
                    val out = open.getOrPut(item.file) {
                        c = c.copy(bodies = c.bodies + 1)
                        BufferedOutputStream(FileOutputStream(File(dir, item.file), true))
                    }
                    if (c.bytes + item.bytes.size > maxBytes) {
                        c = c.copy(droppedBodyBytes = c.droppedBodyBytes + item.bytes.size, errors = c.errors + 1)
                    } else {
                        out.write(item.bytes)
                        c = c.copy(bytes = c.bytes + item.bytes.size)
                    }
                    if (item.last) open.remove(item.file)?.close()
                }
            }
        }
        events.flush()
        lastSeq = seq
        counters = c.copy(batches = c.batches + 1)
        return true
    }

    fun close() {
        if (closed) return
        closed = true
        events.close()
        open.values.forEach { it.close() }
        open.clear()
    }
}

/** Result of [Finalizer.finalize]. */
class FinalizeResult(
    val id: String,
    val ok: Boolean,
    val message: String,
    val files: Int,
    val bytes: Long,
    val eligibleSecrets: Int,
    val replacements: Map<String, Int>,
    val verifyHits: Int,
)

/** The finalize pass: layer 2 over every stored file, a verification scan, then checksums and FINALIZED. */
object Finalizer {
    /**
     * Finalizes [writer]'s session with the remembered [secrets]. [meta] goes into session.json
     * (counters and facts from the app and the extension; never secret values). On any failure
     * the session is deleted, so an unscrubbed session never stays on disk.
     */
    fun finalize(
        store: CaptureStore,
        writer: SessionWriter,
        secrets: List<Secret>,
        meta: Map<String, Any?>,
        options: TaintOptions = TaintOptions(),
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): FinalizeResult {
        val dir = writer.dir
        try {
            writer.close()
            val scrubber = TaintScrubber(secrets, options)
            val dataFiles = dataFiles(dir)
            val totals = LinkedHashMap<String, Int>()
            dataFiles.forEachIndexed { i, f ->
                scrubFile(f, scrubber).forEach { (label, n) -> totals[label] = (totals[label] ?: 0) + n }
                progress(i + 1, dataFiles.size * 2)
            }
            var verifyHits = 0
            dataFiles.forEachIndexed { i, f ->
                verifyHits += countHits(f, scrubber)
                progress(dataFiles.size + i + 1, dataFiles.size * 2)
            }
            if (verifyHits != 0) {
                dir.deleteRecursively()
                return FinalizeResult(writer.id, false, "verification found $verifyHits remaining secret occurrences; session deleted", 0, 0, scrubber.eligibleValues, totals, verifyHits)
            }
            val bytes = dataFiles.sumOf { it.length() }
            val session = LinkedHashMap<String, Any?>()
            session["format"] = CaptureStore.FORMAT_VERSION
            session["id"] = writer.id
            session.putAll(meta)
            session["writer"] = writer.counters.let {
                mapOf("lines" to it.lines, "bodies" to it.bodies, "bytes" to it.bytes, "droppedBodyBytes" to it.droppedBodyBytes, "errors" to it.errors, "batches" to it.batches, "duplicateBatches" to it.duplicateBatches)
            }
            session["taint"] = mapOf(
                "minTaintLength" to options.minLength,
                "rememberedValues" to secrets.size,
                "eligibleValues" to scrubber.eligibleValues,
                "patterns" to scrubber.patternCount,
                "replacements" to totals.values.sum(),
                "byLabel" to totals,
                "labels" to secrets.map { TaintScrubber.sanitizeLabel(it.label) }.distinct().sorted(),
                "verifyHits" to verifyHits,
            )
            session["files"] = dataFiles.size
            session["dataBytes"] = bytes
            // session.json holds no data from pages, but it is scrubbed too, for certainty.
            File(dir, CaptureStore.SESSION).writeBytes(scrubber.scrub((JsonWriter.write(session) + "\n").toByteArray(Charsets.UTF_8)).bytes)
            val checksums = (dataFiles + File(dir, CaptureStore.SESSION))
                .map { "${sha256(it)}  ${it.relativeTo(dir).invariantSeparatorsPath}" }
                .sorted()
                .joinToString("") { "$it\n" }
            val checksumFile = File(dir, CaptureStore.CHECKSUMS)
            checksumFile.writeText(checksums)
            val tmp = File(dir, "${CaptureStore.FINALIZED}.tmp")
            tmp.writeText("twinbook-capture ${CaptureStore.FORMAT_VERSION}\nchecksums ${sha256(checksumFile)}\n")
            check(tmp.renameTo(File(dir, CaptureStore.FINALIZED))) { "could not write FINALIZED" }
            File(dir, CaptureStore.OPEN).delete()
            return FinalizeResult(writer.id, true, "finalized", dataFiles.size, bytes, scrubber.eligibleValues, totals, 0)
        } catch (e: Exception) {
            dir.deleteRecursively()
            return FinalizeResult(writer.id, false, "finalize failed (${e.javaClass.simpleName}); session deleted", 0, 0, 0, emptyMap(), -1)
        }
    }

    /** events.ndjson and every body file. */
    fun dataFiles(dir: File): List<File> =
        listOf(File(dir, CaptureStore.EVENTS)).filter { it.isFile } +
            (File(dir, "bodies").listFiles() ?: emptyArray()).filter { it.isFile }.sortedBy { it.name }

    /** Scrubs a file in place (through a temporary file). events.ndjson is processed line by line. */
    private fun scrubFile(f: File, scrubber: TaintScrubber): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        val tmp = File(f.parentFile, "${f.name}.scrub")
        if (f.name == CaptureStore.EVENTS) {
            BufferedOutputStream(FileOutputStream(tmp)).use { out ->
                forEachLine(f) { line ->
                    val r = scrubber.scrub(line)
                    r.counts.forEach { (k, v) -> counts[k] = (counts[k] ?: 0) + v }
                    out.write(r.bytes)
                    out.write('\n'.code)
                }
            }
        } else {
            val r = scrubber.scrub(f.readBytes())
            r.counts.forEach { (k, v) -> counts[k] = (counts[k] ?: 0) + v }
            tmp.writeBytes(r.bytes)
        }
        check(tmp.renameTo(f)) { "could not replace ${f.name}" }
        return counts
    }

    private fun countHits(f: File, scrubber: TaintScrubber): Int {
        if (f.name != CaptureStore.EVENTS) return scrubber.countHits(f.readBytes())
        var hits = 0
        forEachLine(f) { hits += scrubber.countHits(it) }
        return hits
    }

    /** Lines of a file as bytes, without the newline. */
    private fun forEachLine(f: File, block: (ByteArray) -> Unit) {
        f.inputStream().buffered(256 * 1024).use { input ->
            val line = java.io.ByteArrayOutputStream(4096)
            while (true) {
                val b = input.read()
                if (b < 0) break
                if (b == '\n'.code) {
                    block(line.toByteArray())
                    line.reset()
                } else {
                    line.write(b)
                }
            }
            if (line.size() > 0) block(line.toByteArray())
        }
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
