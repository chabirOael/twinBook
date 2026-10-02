package io.github.chabiroael.twinbook.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DataStatusTest {
    @Test
    fun describeNamesTheModule() {
        assertEquals("Data: placeholder, no models yet", DataStatus().describe())
    }
}
