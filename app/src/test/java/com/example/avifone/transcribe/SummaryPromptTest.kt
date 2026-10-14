package com.example.avifone.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryPromptTest {

    private val lines = (1..50).map { "[10:%02d] Speaker %d: line number %d with some words".format(it, it % 3 + 1, it) }
    private val transcript = lines.joinToString("\n")

    @Test
    fun shortTranscriptIsUntouched() {
        assertEquals(transcript, SummaryPrompt.truncate(transcript, maxChars = transcript.length))
    }

    @Test
    fun keepsTheNewestWholeLinesWithinBudget() {
        val max = 400
        val out = SummaryPrompt.truncate(transcript, maxChars = max)
        assertTrue(out.length <= max)
        assertTrue(out.startsWith(SummaryPrompt.OMITTED_MARKER + "\n"))
        val kept = out.lines().drop(1)
        assertEquals(lines.takeLast(kept.size), kept)
        assertTrue(kept.isNotEmpty())
        assertTrue(kept.last().endsWith("line number 50 with some words"))
    }

    @Test
    fun singleOversizedLineIsCutToItsTail() {
        val long = "x".repeat(1000) + "END"
        val out = SummaryPrompt.truncate(long, maxChars = 100)
        assertTrue(out.length <= 100)
        assertTrue(out.endsWith("END"))
        assertTrue(out.startsWith(SummaryPrompt.OMITTED_MARKER))
    }

    @Test
    fun markerOnlyWhenSomethingWasDropped() {
        assertFalse(SummaryPrompt.truncate("a\nb", maxChars = 10).contains(SummaryPrompt.OMITTED_MARKER))
    }

    @Test
    fun cloudPromptKeepsTheInstructionsAndTranscript() {
        val p = SummaryPrompt.cloud("[10:00] Ana: hi")
        assertTrue(p.contains("Summarize it for someone who just joined."))
        assertTrue(p.endsWith("[10:00] Ana: hi"))
        assertFalse(p.contains(SummaryPrompt.JSON_INSTRUCTION))
    }

    @Test
    fun localPromptAsksForJsonAndIsBounded() {
        val p = SummaryPrompt.local(transcript + "\n" + "y".repeat(20_000))
        assertTrue(p.contains(SummaryPrompt.JSON_INSTRUCTION))
        assertTrue(p.length < SummaryPrompt.LOCAL_MAX_CHARS + SummaryPrompt.INSTRUCTIONS.length + SummaryPrompt.JSON_INSTRUCTION.length + 10)
    }
}
