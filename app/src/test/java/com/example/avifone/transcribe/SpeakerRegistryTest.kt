package com.example.avifone.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeakerRegistryTest {

    @Test
    fun defaultNamesAndColorsFollowTheLabel() {
        val r = SpeakerRegistry()
        assertEquals("Speaker 3", r.speaker("S3").name)
        assertEquals(2, r.speaker("S3").colorIndex)
    }

    @Test
    fun renameAndBlankRenameRestoresDefault() {
        val r = SpeakerRegistry()
        r.rename("S2", "  Marco ")
        assertEquals("Marco", r.speaker("S2").name)
        r.rename("S2", " ")
        assertEquals("Speaker 2", r.speaker("S2").name)
    }

    @Test
    fun mergeRedirectsLabelAndReportsWhatToMerge() {
        val r = SpeakerRegistry()
        r.rename("S1", "Priya")
        assertEquals("S3" to "S1", r.merge("S3", "S1"))
        assertEquals("S1", r.resolve("S3"))
        assertEquals("Priya", r.speaker("S3").name)
        assertEquals(0, r.speaker("S3").colorIndex)
        // Already the same person: nothing to do.
        assertNull(r.merge("S3", "S1"))
        // Chains resolve to the final target.
        r.merge("S1", "S2")
        assertEquals("S2", r.resolve("S3"))
    }
}
