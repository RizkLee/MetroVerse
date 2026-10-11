package com.metrolist.music.constants

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioQualityTest {
    @Test
    fun `a missing audio quality preference selects high`() {
        assertEquals(AudioQuality.HIGH, AudioQuality.DEFAULT)
        assertEquals(AudioQuality.HIGH, AudioQuality.fromPreference(null))
    }

    @Test
    fun `explicit user choices including automatic remain intact`() {
        AudioQuality.entries.forEach { assertEquals(it, AudioQuality.fromPreference(it.name)) }
    }

    @Test
    fun `legacy and invalid quality values retain a usable high default`() {
        assertEquals(AudioQuality.HIGH, AudioQuality.fromPreference("VERY_HIGH"))
        assertEquals(AudioQuality.HIGH, AudioQuality.fromPreference("unsupported"))
    }
}
