package com.example.avifone.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeakerRegistryTest {

    private val chunk = FloatArray(30 * 16_000) { it.toFloat() }

    @Test
    fun learnsClipFromLongestCleanSegmentCappedAtFiveSeconds() {
        val registry = SpeakerRegistry()
        registry.learn(
            chunk,
            listOf(
                ChunkSegment("S1", 0.0, 2.5, "a"),
                ChunkSegment("S1", 10.0, 18.0, "long"),   // 8 s -> middle 5 s: 11.5..16.5
                ChunkSegment("S2", 20.0, 21.0, "short"),  // too short for a clip
            ),
        )
        val s1 = registry.speaker("S1")
        assertEquals(5 * 16_000, s1.clip!!.size)
        assertEquals(11.5f * 16_000, s1.clip!![0], 1f)
        assertNull(registry.speaker("S2").clip)
        assertEquals(listOf("S1"), registry.referenceSpeakers().map { it.label })
    }

    @Test
    fun ignoresSegmentsThatOverlapAnotherSpeaker() {
        val registry = SpeakerRegistry()
        registry.learn(
            chunk,
            listOf(ChunkSegment("S1", 0.0, 6.0, "talking over"), ChunkSegment("S2", 5.0, 9.0, "interrupt")),
        )
        assertNull(registry.speaker("S1").clip)
        assertNull(registry.speaker("S2").clip)
    }

    @Test
    fun freeLabelsPreferNeverUsedLabels() {
        val registry = SpeakerRegistry()
        registry.learn(chunk, listOf(ChunkSegment("S1", 0.0, 4.0, "clip"), ChunkSegment("S2", 5.0, 5.5, "hi")))
        // S1 has a clip; S2 was seen but has none, so it comes after unseen S3/S4.
        assertEquals(listOf("S3", "S4", "S2"), registry.freeLabels())
    }

    @Test
    fun mergeRedirectsLabelsAndRemovesTheirClip() {
        val registry = SpeakerRegistry()
        registry.learn(chunk, listOf(ChunkSegment("S1", 0.0, 4.0, "a"), ChunkSegment("S3", 5.0, 9.0, "b")))
        registry.rename("S1", "Priya")
        registry.merge("S3", "S1")
        assertEquals("S1", registry.resolve("S3"))
        assertEquals("Priya", registry.speaker("S3").name)
        assertEquals(listOf("S1"), registry.referenceSpeakers().map { it.label })
        // A merged-away label must not be handed to a new voice.
        assertEquals(listOf("S2", "S4"), registry.freeLabels())
    }

    @Test
    fun renameToBlankRestoresDefaultName() {
        val registry = SpeakerRegistry()
        registry.rename("S2", "  ")
        assertEquals("Speaker 2", registry.speaker("S2").name)
        assertEquals(1, registry.speaker("S2").colorIndex)
    }
}
