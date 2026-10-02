package io.github.chabiroael.twinbook.capture

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStoreTest {
    private val root: File = Files.createTempDirectory("captures").toFile()
    private val store = CaptureStore(root)

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private val token = "NAcMockToken_abcdef:17:1696"
    private val cookie = "100012345678901"

    private fun body(text: String, file: String = "bodies/7-1.res", last: Boolean = true) = CaptureItem.Body(file, text.toByteArray(Charsets.UTF_8), last)

    @Test
    fun finalizeScrubsValuesRecordedBeforeTheyWereRecognized() {
        val w = store.create("s1")
        // The HTML body comes first: the token is not keyed there, so layer 1 left it alone.
        assertTrue(w.append(1, listOf(CaptureItem.Line("""{"ev":"request","rid":"7"}"""), body("<script>boot(\"$token\", $cookie)</script>", last = false))))
        assertTrue(w.append(2, listOf(body(" tail ${token.replace(":", "%3A")}"), CaptureItem.Line("""{"ev":"body","rid":"7"}"""))))
        // Later the token is seen under its key: the extension remembered it.
        assertTrue(w.append(3, listOf(CaptureItem.Line("""{"ev":"request","rid":"8","body":{"fields":[["fb_dtsg","!R*********************!"]]},"echo":"$cookie"}"""))))
        // A re-sent batch is ignored.
        assertFalse(w.append(3, listOf(CaptureItem.Line("duplicate"))))
        assertEquals(1, w.counters.duplicateBatches)

        val secrets = listOf(Secret(token, "field:fb_dtsg"), Secret(cookie, "cookie:c_user"), Secret("25510", "field:jazoest"))
        val result = Finalizer.finalize(store, w, secrets, mapOf("profiles" to listOf("mock")))
        assertTrue(result.message, result.ok)
        assertEquals(0, result.verifyHits)
        assertEquals(mapOf("field:fb_dtsg" to 2, "cookie:c_user" to 2), result.replacements)

        val dir = store.dir("s1")
        val all = dir.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertFalse(all.contains(token))
        assertFalse(all.contains(token.replace(":", "%3A")))
        assertFalse(all.contains(cookie))
        assertEquals("<script>boot(\"!T:field:fb_dtsg!\", !T:cookie:c_user!)</script> tail !T:field:fb_dtsg!", File(dir, "bodies/7-1.res").readText())
        assertFalse(File(dir, "events.ndjson").readText().contains("duplicate"))
        assertTrue(store.isFinalized("s1"))
        assertFalse(File(dir, CaptureStore.OPEN).exists())

        // session.json names labels and counts, never values.
        @Suppress("UNCHECKED_CAST")
        val session = MiniJson.parse(File(dir, "session.json").readText()) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val taint = session["taint"] as Map<String, Any?>
        assertEquals(listOf("cookie:c_user", "field:fb_dtsg", "field:jazoest"), taint["labels"])
        assertEquals(2L, taint["eligibleValues"])
        assertEquals(0L, taint["verifyHits"])

        // checksums.sha256 covers every other file and FINALIZED pins it.
        val lines = File(dir, "checksums.sha256").readLines()
        assertEquals(listOf("bodies/7-1.res", "events.ndjson", "session.json"), lines.map { it.substringAfter("  ") }.sorted())
        for (l in lines) assertEquals(l, Finalizer.sha256(File(dir, l.substringAfter("  "))), l.substringBefore("  "))
        assertEquals("twinbook-capture 1\nchecksums ${Finalizer.sha256(File(dir, "checksums.sha256"))}\n", File(dir, "FINALIZED").readText())
    }

    @Test
    fun unfinalizedSessionsAreDeletedAtStart() {
        val open = store.create("open1")
        open.append(1, listOf(CaptureItem.Line("{}"), body("secret $token")))
        open.close()
        val done = store.create("done1")
        done.append(1, listOf(CaptureItem.Line("{}")))
        assertTrue(Finalizer.finalize(store, done, emptyList(), emptyMap()).ok)
        assertEquals(mapOf("done1" to true, "open1" to false), store.list().associate { it.id to it.finalized })
        assertEquals(listOf("open1"), store.deleteUnfinalized())
        assertEquals(listOf("done1"), store.list().map { it.id })
    }

    @Test
    fun rejectsBadNamesAndCapsTheSessionSize() {
        val w = store.create("cap1", maxBytes = 100)
        w.append(1, listOf(CaptureItem.Body("../escape.res", ByteArray(3), true), body("x".repeat(80)), CaptureItem.Body("bodies/9-2.res", ByteArray(50), true)))
        assertEquals(2, w.counters.errors)
        assertEquals(50, w.counters.droppedBodyBytes)
        assertFalse(File(root, "escape.res").exists())
        w.close()
    }
}
