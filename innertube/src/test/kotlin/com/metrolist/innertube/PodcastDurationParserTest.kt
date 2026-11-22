/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.innertube

import com.metrolist.innertube.utils.parseTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PodcastDurationParserTest {
    @Test
    fun `parses colon dot comma and english text podcast durations`() {
        assertEquals(225, "3:45".parseTime())
        assertEquals(225, "3.45".parseTime())
        assertEquals(3723, "1:02:03".parseTime())
        assertEquals(2700, "45 min".parseTime())
        assertEquals(4830, "1 hr 20 min 30 sec".parseTime())
        assertEquals(7500, "2 hours 5 minutes".parseTime())
        assertEquals(30, "30 sec".parseTime())
        assertNull("".parseTime())
        assertNull("Episode 1".parseTime())
    }
}
