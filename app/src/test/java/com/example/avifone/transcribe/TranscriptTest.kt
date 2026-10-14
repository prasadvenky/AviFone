package com.example.avifone.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTest {

    @Test
    fun keepsLinesInTimeOrder() {
        val t = Transcript()
        t.add("S2", "later", startMs = 5_000, endMs = 6_000)
        t.add("S1", "first", startMs = 1_000, endMs = 2_500)
        assertEquals(listOf("first", "later"), t.all.map { it.text })
    }

    @Test
    fun pruneDropsLinesOlderThanTheWindow() {
        val t = Transcript(windowMs = 15 * 60_000L)
        t.add("S1", "old", 0, 1_000)
        t.add("S1", "recent", 10 * 60_000L, 10 * 60_000L + 1_000)
        assertFalse(t.prune(nowMs = 15 * 60_000L))           // "old" ended 14:59 ago
        assertTrue(t.prune(nowMs = 16 * 60_000L))
        assertEquals(listOf("recent"), t.all.map { it.text })
    }

    @Test
    fun promptTextUsesCurrentNamesAndMerges() {
        val registry = SpeakerRegistry()
        registry.rename("S1", "Priya")
        registry.merge("S3", "S1")
        val t = Transcript()
        t.add("S1", "hello", 0, 1_000)
        t.add("S2", "hi", 2_000, 3_000)
        t.add("S3", "it's me again", 4_000, 5_000)
        val lines = t.toPromptText(registry).lines()
        assertTrue(lines[0], lines[0].endsWith("Priya: hello"))
        assertTrue(lines[1], lines[1].endsWith("Speaker 2: hi"))
        assertTrue(lines[2], lines[2].endsWith("Priya: it's me again"))
    }
}
