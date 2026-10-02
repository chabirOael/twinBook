package io.github.chabiroael.twinbook.capture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the layer 2 vectors shared with the extension (extension/test/vectors/taint.json). */
class TaintScrubberTest {
    @Suppress("UNCHECKED_CAST")
    private val vectors = MiniJson.parse(File(System.getProperty("twinbook.taintVectors")).readText()) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    @Test
    fun sharedVectors() {
        val options = TaintOptions(minLength = (vectors["minTaintLength"] as Long).toInt(), stopList = vectors["stopList"] as List<String>)
        val cases = vectors["cases"] as List<Map<String, Any?>>
        assertTrue(cases.size >= 10)
        for (c in cases) {
            val name = c["name"] as String
            val secrets = (c["secrets"] as List<Map<String, String>>).map { Secret(it.getValue("value"), it.getValue("label")) }
            val scrubber = TaintScrubber(secrets, options)
            val result = scrubber.scrub((c["input"] as String).toByteArray(Charsets.UTF_8))
            assertEquals(name, c["output"], result.bytes.toString(Charsets.UTF_8))
            assertEquals(name, (c["counts"] as Map<String, Long>).mapValues { it.value.toInt() }, result.counts)
            assertEquals(name, 0, scrubber.countHits(result.bytes))
            if (c["validJson"] == true) {
                parseDeep(c["input"] as String)
                parseDeep(result.bytes.toString(Charsets.UTF_8))
            }
        }
        assertTrue(cases.count { it["validJson"] == true } >= 6)
    }

    /** Parses [text] as JSON, and every string inside it that starts like JSON, recursively. */
    private fun parseDeep(text: String) {
        fun visit(v: Any?) {
            when (v) {
                is String -> if (v.startsWith("[") || v.startsWith("{")) parseDeep(v)
                is List<*> -> v.forEach(::visit)
                is Map<*, *> -> v.values.forEach(::visit)
            }
        }
        visit(MiniJson.parse(text))
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun defaultsMatchTheRulesFile() {
        val rules = MiniJson.parse(File(System.getProperty("twinbook.redactionRules")).readText()) as Map<String, Any?>
        assertEquals(TaintOptions().minLength.toLong(), rules["minTaintLength"])
        assertEquals(TaintOptions().stopList, rules["taintStopList"])
        assertEquals(vectors["stopList"], rules["taintStopList"])
    }

    @Test
    fun encodingsMatchJavaScript() {
        assertEquals("a%20b%2Fc%22d%C3%A9%F0%9F%98%80-_.!~*'()", TaintScrubber.encodeUriComponent("a b/c\"dé😀-_.!~*'()"))
        assertNull(TaintScrubber.encodeUriComponent("x\uD800y"))
        assertEquals("\\\"\\\\\\n\\u0001é😀\\ud800", TaintScrubber.jsonEscape("\"\\\n\u0001é😀\uD800"))
        assertEquals("a&amp;&lt;&gt;&quot;&#39;", TaintScrubber.htmlEscape("a&<>\"'"))
        assertEquals("cookie:we_ird__", TaintScrubber.sanitizeLabel("cookie:we ird😀"))
        assertFalse(TaintScrubber.isEligible("😀😀😀😀😀"))
    }
}
