package io.github.chabiroael.twinbook.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.chabiroael.twinbook.engine.TestEngine.evidence
import io.github.chabiroael.twinbook.engine.TestEngine.server
import io.github.chabiroael.twinbook.mockserver.MockPages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** G10: main-document HTML responses have ad edges removed from their JSON islands. */
@RunWith(AndroidJUnit4::class)
class DocumentFilterTest {
    @Test
    fun jsonIslandsInTheMainDocumentAreFiltered() = runBlocking {
        TestEngine.ready()
        val session = TestEngine.newSession(UserAgentProfile.MOBILE, "document")
        try {
            val run = TestEngine.newRun("document")
            withContext(Dispatchers.Main) { session.load(server.url("/document?run=$run")) }
            val report = JSONObject(withContext(Dispatchers.IO) { server.awaitReport(run, 60_000) })
            val islands = report.getJSONArray("islands")
            assertEquals(2, islands.length())
            assertEquals(MockPages.DOCUMENT_ISLAND_FILTERED, islands.getString(0))
            assertEquals(MockPages.DOCUMENT_ISLAND_UNTOUCHED, islands.getString(1))
            for (id in MockPages.DOCUMENT_AD_IDS) assertFalse(islands.getString(0).contains(id))
            val stats = TestEngine.awaitEvent("filter.stats") { it.optString("kind") == "document" && it.optString("url").contains("run=$run") }
            assertEquals(1, stats.data.getJSONObject("stats").getInt("changed"))
            evidence("G10 document: island 1 lost ${MockPages.DOCUMENT_AD_IDS}, island 2 byte-identical; filter stats ${stats.data.getJSONObject("stats")}")
        } finally {
            TestEngine.onMain { session.close() }
        }
    }
}
