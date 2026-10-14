package com.example.avifone.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTest {

    @Test
    fun placesSegmentsInWallClockTimeInOrder() {
        val t = Transcript()
        t.addChunk(1_000_000, listOf(ChunkSegment("S2", 5.0, 6.0, "later"), ChunkSegment("S1", 1.0, 2.5, "first")))
        assertEquals(listOf("first", "later"), t.all.map { it.text })
        assertEquals(1_001_000, t.all[0].startMs)
        assertEquals(1_002_500, t.all[0].endMs)
    }

    @Test
    fun pruneDropsLinesOlderThanTheWindow() {
        val t = Transcript(windowMs = 15 * 60_000L)
        t.addChunk(0, listOf(ChunkSegment("S1", 0.0, 1.0, "old")))
        t.addChunk(10 * 60_000L, listOf(ChunkSegment("S1", 0.0, 1.0, "recent")))
        assertFalse(t.prune(nowMs = 15 * 60_000L))           // "old" ended 14:59 ago
        assertTrue(t.prune(nowMs = 16 * 60_000L))
        assertEquals(listOf("recent"), t.all.map { it.text })
    }

    @Test
    fun promptTextUsesCurrentNamesAndSkipsGaps() {
        val registry = SpeakerRegistry()
        registry.rename("S1", "Priya")
        val t = Transcript()
        t.addChunk(0, listOf(ChunkSegment("S1", 0.0, 1.0, "hello"), ChunkSegment("S2", 2.0, 3.0, "hi")))
        t.addGap(5_000, 35_000)
        val lines = t.toPromptText(registry).lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].endsWith("Priya: hello"))
        assertTrue(lines[1], lines[1].endsWith("Speaker 2: hi"))
    }
}
