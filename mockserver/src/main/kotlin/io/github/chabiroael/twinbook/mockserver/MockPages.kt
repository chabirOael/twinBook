package io.github.chabiroael.twinbook.mockserver

/** Small HTML pages the mock server serves. */
object MockPages {
    fun page(title: String, body: String): String =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>$title</title></head><body>$body</body></html>"

    /** The replay anchor: a same-origin page with no scripts at all. */
    val ANCHOR = page("anchor", "<p id=\"anchor\">twinBook anchor page</p>")

    val BLANK = page("blank", "")

    private fun edge(i: Int, ad: Boolean): Map<String, Any?> = linkedMapOf(
        "node" to linkedMapOf<String, Any?>("__typename" to "Story", "id" to "doc-story-$i", "text" to "document post $i é😀")
            .apply { if (ad) put("mock_sponsored", true) },
        "cursor" to "doc-cursor-$i",
    )

    private fun island(edges: List<Map<String, Any?>>): Map<String, Any?> = linkedMapOf(
        "require" to listOf(
            listOf(
                "ScheduledServerJS",
                "handle",
                null,
                listOf(
                    linkedMapOf(
                        "__bbox" to linkedMapOf(
                            "result" to linkedMapOf(
                                "data" to linkedMapOf("viewer" to linkedMapOf("news_feed" to linkedMapOf("edges" to edges))),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private val DOCUMENT_ADS = setOf(0, 2)
    private val documentEdges = (0 until 4).map { edge(it, it in DOCUMENT_ADS) }

    val DOCUMENT_AD_IDS: List<String> = DOCUMENT_ADS.sorted().map { "doc-story-$it" }

    /** First island as served: contains ad edges. */
    val DOCUMENT_ISLAND_WITH_ADS: String = Json.write(island(documentEdges))

    /** First island as the page must receive it after filtering. */
    val DOCUMENT_ISLAND_FILTERED: String = Json.write(island(documentEdges.filterIndexed { i, _ -> i !in DOCUMENT_ADS }))

    /** Second island: no ads, odd formatting; must arrive byte-identical. */
    const val DOCUMENT_ISLAND_UNTOUCHED: String = """ { "config" : { "x": 1, "text": "é 😀" }, "edges": [ {"node": {"id": "cfg"}} ] } """

    private const val DOCUMENT_SCRIPT = """
(function () {
  var run = new URLSearchParams(location.search).get("run") || "";
  var islands = Array.prototype.map.call(
    document.querySelectorAll('script[type="application/json"]'),
    function (s) { return s.textContent; });
  var xhr = new XMLHttpRequest();
  xhr.open("POST", "/report?run=" + encodeURIComponent(run));
  xhr.setRequestHeader("Content-Type", "application/json");
  xhr.send(JSON.stringify({ run: run, kind: "document", islands: islands }));
})();
"""

    /** HTML document with two JSON islands; its inline script reports what it received. */
    fun document(): String = page(
        "mock document",
        "<p id=\"msg\">mock document é😀</p>" +
            "<script type=\"application/json\" data-content-len=\"1\">$DOCUMENT_ISLAND_WITH_ADS</script>" +
            "<script type=\"application/json\" id=\"config\">$DOCUMENT_ISLAND_UNTOUCHED</script>" +
            "<script>$DOCUMENT_SCRIPT</script>",
    )
}
