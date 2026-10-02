package io.github.chabiroael.twinbook.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineStatusTest {
    @Test
    fun describeNamesTheModule() {
        assertEquals("Engine: placeholder, no browser engine yet", EngineStatus().describe())
    }
}
