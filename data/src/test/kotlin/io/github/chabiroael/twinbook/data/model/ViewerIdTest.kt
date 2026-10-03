package io.github.chabiroael.twinbook.data.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerIdTest {
    @Test
    fun bothFormsAreTheViewer() {
        for (v in listOf<Any?>("!T:cookie:c_user!", 0, 0L, 0.0, "0")) assertTrue("$v", ViewerId.matches(v))
        for (v in listOf<Any?>(1, "1", "!T:cookie:xs!", "!R*****!", null, "", listOf(0))) assertFalse("$v", ViewerId.matches(v))
    }

    /** The committed fixtures hold the quoted form for the viewer's string ids. */
    @Test
    fun fixturesUseTheQuotedPlaceholder() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "fixtures/manifest.json").exists() }
        val ids = Regex("\"id\":\"([^\"]*)\"")
        var viewer = 0
        File(root, "fixtures/graphql").walkTopDown().filter { it.isFile && it.name.endsWith(".ndjson") }.forEach { f ->
            viewer += ids.findAll(f.readText()).count { ViewerId.matches(it.groupValues[1]) }
        }
        assertTrue("viewer ids found in fixtures: $viewer", viewer > 0)
        assertEquals("!T:cookie:c_user!", ViewerId.PLACEHOLDER)
    }
}
