package io.github.chabiroael.twinbook.mockserver

/**
 * Streamed GraphQL-like responses that imitate the shape of the site's traffic.
 *
 * Every response is newline-delimited JSON. The first document carries a list at
 * `data.viewer.news_feed.edges`. Each later document delivers one more edge, with a `path`
 * such as `["viewer","news_feed","edges",5]` and `extensions.is_final`, true only on the last
 * document. Mock ad marker: an edge whose `node` has `"mock_sponsored": true`.
 *
 * Each scenario also carries [Scenario.expectedText], the exact body the page must receive
 * when twin-bridge filters it: ad edges removed from the first document (which the filter
 * re-serializes with `JSON.stringify`; [Json] writes the same bytes), later ad documents
 * dropped, a dropped final document replaced by `{"extensions":{"is_final":true}}`, every other
 * line unchanged.
 */
class Scenario(
    val name: String,
    val description: String,
    /** The unfiltered body. */
    val body: ByteArray,
    /** Byte offsets where the body is cut into separately flushed chunks. */
    val cuts: List<Int>,
    /** Delay before each chunk after the first. */
    val delayMs: Long,
    val gzip: Boolean,
    /** Exact body expected after filtering. */
    val expectedText: String,
    /** IDs of the ad edges in this response. */
    val adIds: List<String>,
    /** IDs of all edges, in order. */
    val allIds: List<String>,
    /** Lines that are not valid JSON (they must pass unchanged). */
    val malformedLines: List<String>,
) {
    val bodyText: String get() = body.toString(Charsets.UTF_8)

    /** Body split at [cuts]. */
    fun chunks(): List<ByteArray> {
        val points = listOf(0) + cuts.filter { it in 1 until body.size }.distinct().sorted() + body.size
        return points.zipWithNext { a, b -> body.copyOfRange(a, b) }
    }
}

object Scenarios {
    const val GUARD = "for (;;);"
    const val FINAL_REPLACEMENT = """{"extensions":{"is_final":true}}"""
    const val MULTIBYTE_TEXT = "é ü 😀 مرحبا 中文 ✓"

    private val byName: Map<String, Scenario> by lazy {
        listOf(
            feed("ads-first", "ad at the first position of the first document's list", ads = setOf(0)),
            feed("ads-middle", "ads in the middle of the list and in a middle later document", ads = setOf(2, 5)),
            feed("ads-last", "ad in the final document, replaced by a minimal final document", ads = setOf(7)),
            feed("ads-all", "ads at first, middle and last position", ads = setOf(0, 2, 5, 7)),
            feed("no-ads", "no ads at all: output must equal input", ads = emptySet()),
            feed("no-guard", "ads at all positions, no guard prefix", ads = setOf(0, 2, 5, 7), guard = false),
            feed("split-line", "chunk boundaries every 37 bytes, inside lines", ads = setOf(0, 5, 7), cut = Cut.Every(37)),
            feed(
                "split-utf8",
                "chunk boundaries inside multi-byte UTF-8 characters",
                ads = setOf(0, 5, 7),
                text = MULTIBYTE_TEXT,
                cut = Cut.InsideMultibyte,
            ),
            feed("gzip", "gzip content encoding, one sync-flushed chunk per line", ads = setOf(0, 2, 5, 7), gzip = true),
            feed("malformed", "a malformed line in the middle; ads before and after it", ads = setOf(0, 5, 7), malformedAfter = 5),
            feed("slow", "one line per chunk, 400 ms apart, for progressive delivery", ads = setOf(5), delayMs = 400),
            large(),
        ).associateBy { it.name }
    }

    val names: List<String> get() = byName.keys.toList()

    fun get(name: String): Scenario = byName[name] ?: throw IllegalArgumentException("unknown scenario $name")

    fun find(name: String): Scenario? = byName[name]

    private sealed interface Cut {
        data object PerLine : Cut
        data class Every(val bytes: Int) : Cut
        data object InsideMultibyte : Cut
        data class Fixed(val bytes: Int) : Cut
    }

    private fun edge(i: Int, ad: Boolean, text: String): Map<String, Any?> = linkedMapOf(
        "node" to linkedMapOf<String, Any?>(
            "__typename" to "Story",
            "id" to "story-$i",
            "text" to "post $i $text",
        ).apply { if (ad) put("mock_sponsored", true) },
        "cursor" to "cursor-$i",
    )

    private fun firstDoc(edges: List<Map<String, Any?>>, final: Boolean): Map<String, Any?> = linkedMapOf(
        "data" to linkedMapOf("viewer" to linkedMapOf("news_feed" to linkedMapOf("edges" to edges))),
        "extensions" to linkedMapOf("is_final" to final),
    )

    private fun laterDoc(i: Int, edge: Map<String, Any?>, final: Boolean): Map<String, Any?> = linkedMapOf(
        "label" to "MockFeedQuery\$stream\$edges",
        "path" to listOf("viewer", "news_feed", "edges", i),
        "data" to edge,
        "extensions" to linkedMapOf("is_final" to final),
    )

    private fun feed(
        name: String,
        description: String,
        ads: Set<Int>,
        guard: Boolean = true,
        text: String = "plain text",
        firstCount: Int = 4,
        laterCount: Int = 4,
        cut: Cut = Cut.PerLine,
        gzip: Boolean = false,
        delayMs: Long = 20,
        malformedAfter: Int? = null,
        padding: String = "",
    ): Scenario {
        val total = firstCount + laterCount
        val edges = (0 until total).map { edge(it, it in ads, if (padding.isEmpty()) text else "$text $padding") }
        val first = firstDoc(edges.take(firstCount), final = laterCount == 0)
        val firstKept = firstDoc(edges.take(firstCount).filterIndexed { i, _ -> i !in ads }, final = laterCount == 0)

        val lines = mutableListOf<String>()
        val expected = mutableListOf<String>()
        val malformed = mutableListOf<String>()
        lines += Json.write(first)
        expected += if (ads.any { it < firstCount }) Json.write(firstKept) else lines[0]
        for (i in firstCount until total) {
            val final = i == total - 1
            val line = Json.write(laterDoc(i, edges[i], final))
            lines += line
            when {
                i !in ads -> expected += line
                final -> expected += FINAL_REPLACEMENT
            }
            if (malformedAfter == i) {
                val bad = """{"label":"MockFeedQuery${'$'}stream${'$'}edges","data":{"node":{"id":"broken""""
                lines += bad
                expected += bad
                malformed += bad
            }
        }
        val prefix = if (guard) GUARD else ""
        val bodyText = prefix + lines.joinToString("\n") + "\n"
        val body = bodyText.toByteArray(Charsets.UTF_8)
        val cuts = when (cut) {
            Cut.PerLine -> lineEnds(body)
            is Cut.Every -> (cut.bytes until body.size step cut.bytes).toList()
            Cut.InsideMultibyte -> insideMultibyte(body)
            is Cut.Fixed -> (cut.bytes until body.size step cut.bytes).toList()
        }
        return Scenario(
            name = name,
            description = description,
            body = body,
            cuts = cuts,
            delayMs = delayMs,
            gzip = gzip,
            expectedText = prefix + expected.joinToString("\n") + "\n",
            adIds = ads.sorted().map { "story-$it" },
            allIds = (0 until total).map { "story-$it" },
            malformedLines = malformed,
        )
    }

    /** About 5 MB: 20 edges in the first document, then about 5,000 later documents of 1 KB. */
    private fun large(): Scenario {
        val laterCount = 5_000
        val ads = (0 until 20 + laterCount).filter { it % 50 == 0 || it == 20 + laterCount - 1 }.toSet()
        return feed(
            name = "large",
            description = "about 5 MB, ads every 50 edges and in the final document, 16 KB chunks",
            ads = ads,
            firstCount = 20,
            laterCount = laterCount,
            cut = Cut.Fixed(16 * 1024),
            delayMs = 0,
            padding = "x".repeat(950),
        )
    }

    /** Offsets right after each newline, so every line is its own chunk. */
    private fun lineEnds(body: ByteArray): List<Int> = body.indices.filter { body[it] == '\n'.code.toByte() }.map { it + 1 }

    /** Offsets one byte into each multi-byte character (lead byte >= 0xC0), at most one per line. */
    private fun insideMultibyte(body: ByteArray): List<Int> {
        val cuts = mutableListOf<Int>()
        var cutThisLine = 0
        for (i in body.indices) {
            val b = body[i].toInt() and 0xFF
            if (b == '\n'.code) cutThisLine = 0
            if (b >= 0xC0 && cutThisLine < 5) {
                // Cut 1, 2 or 3 bytes into the character, rotating, so all split points occur.
                val width = if (b >= 0xF0) 4 else if (b >= 0xE0) 3 else 2
                cuts += i + 1 + (cuts.size % (width - 1))
                cutThisLine++
            }
        }
        return cuts
    }
}
