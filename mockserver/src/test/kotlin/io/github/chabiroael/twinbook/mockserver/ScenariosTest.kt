package io.github.chabiroael.twinbook.mockserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenariosTest {
    @Test
    fun jsonWriterMatchesJavaScriptStringify() {
        val value = linkedMapOf("a" to listOf(1, true, null, "x\"y\\z\n\t\u0001é😀"), "b" to linkedMapOf<String, Any?>())
        assertEquals("""{"a":[1,true,null,"x\"y\\z\n\t\u0001é😀"],"b":{}}""", Json.write(value))
    }

    @Test
    fun everyScenarioHasAPlausibleExpectation() {
        for (name in Scenarios.names) {
            val s = Scenarios.get(name)
            assertTrue(name, s.body.isNotEmpty())
            assertEquals(name, s.body.size, s.chunks().sumOf { it.size })
            val expectedLines = s.expectedText.trimEnd('\n').split('\n')
            // No ad id survives filtering; every non-ad id does.
            for (id in s.adIds) assertFalse("$name keeps $id", s.expectedText.contains("\"$id\""))
            for (id in s.allIds - s.adIds.toSet()) assertTrue("$name lost $id", s.expectedText.contains("\"$id\""))
            // The last line still says is_final: true.
            assertTrue(name, expectedLines.last().contains("\"is_final\":true"))
        }
    }

    @Test
    fun scenarioWithoutAdsIsUnchanged() {
        val s = Scenarios.get("no-ads")
        assertEquals(s.bodyText, s.expectedText)
    }

    @Test
    fun guardIsPreservedAndOptional() {
        assertTrue(Scenarios.get("ads-all").expectedText.startsWith(Scenarios.GUARD))
        assertFalse(Scenarios.get("no-guard").bodyText.startsWith(Scenarios.GUARD))
    }

    @Test
    fun finalAdIsReplacedAndMiddleAdDropped() {
        val s = Scenarios.get("ads-all")
        val input = s.bodyText.trimEnd('\n').split('\n')
        val output = s.expectedText.trimEnd('\n').split('\n')
        assertEquals(input.size - 1, output.size) // story-5 dropped, story-7 replaced, first doc rewritten
        assertEquals(Scenarios.FINAL_REPLACEMENT, output.last())
        // Untouched later lines are byte-identical.
        assertEquals(input[1], output[1])
    }

    @Test
    fun splitUtf8CutsLandInsideCharacters() {
        val s = Scenarios.get("split-utf8")
        assertTrue(s.cuts.size > 10)
        for (cut in s.cuts) {
            val b = s.body[cut].toInt() and 0xFF
            assertTrue("cut $cut at byte $b is not a continuation byte", b in 0x80..0xBF)
        }
        val widths = s.cuts.map { cut -> (1..3).first { (s.body[cut - it].toInt() and 0xFF) >= 0xC0 } }.toSet()
        assertEquals(setOf(1, 2, 3), widths)
    }

    @Test
    fun splitLineCutsLandInsideLines() {
        val s = Scenarios.get("split-line")
        assertTrue(s.cuts.count { s.body[it - 1] != '\n'.code.toByte() } > 10)
    }

    @Test
    fun largeIsAboutFiveMegabytes() {
        val s = Scenarios.get("large")
        assertTrue(s.body.size in 5_000_000..6_000_000)
        assertTrue(s.chunks().size > 300)
        assertTrue(s.adIds.size > 90)
    }

    @Test
    fun malformedLinePassesThrough() {
        val s = Scenarios.get("malformed")
        assertEquals(1, s.malformedLines.size)
        assertTrue(s.expectedText.contains(s.malformedLines[0] + "\n"))
    }

    @Test
    fun documentIslandsDifferOnlyByAds() {
        assertTrue(MockPages.DOCUMENT_ISLAND_WITH_ADS.contains("mock_sponsored"))
        assertFalse(MockPages.DOCUMENT_ISLAND_FILTERED.contains("mock_sponsored"))
        assertTrue(MockPages.document().contains(MockPages.DOCUMENT_ISLAND_UNTOUCHED))
    }
}
