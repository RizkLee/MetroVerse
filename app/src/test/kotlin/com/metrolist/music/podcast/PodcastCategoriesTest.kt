package com.metrolist.music.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PodcastCategoriesTest {
    @Test
    fun `category slugs and Apple genre ids are unique`() {
        assertEquals(PodcastCategory.entries.size, PodcastCategory.entries.map { it.slug }.toSet().size)
        assertEquals(PodcastCategory.entries.size, PodcastCategory.entries.map { it.appleGenreId }.toSet().size)
    }

    @Test
    fun `category lookup uses stable route slug`() {
        assertEquals(PodcastCategory.TECHNOLOGY, PodcastCategory.fromSlug("technology"))
        assertNull(PodcastCategory.fromSlug("not-a-category"))
    }
}
