package com.example.avifone.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkRequestTest {

    private val chunk = FloatArray(30 * 16_000)

    private fun speaker(label: String, seconds: Double) = SpeakerRegistry.Speaker(
        label = label, name = label, colorIndex = 0, clip = FloatArray((seconds * 16_000).toInt()),
    )

    @Test
    fun withoutReferencesTheChunkIsSentAsIs() {
        val request = ChunkRequest(chunk, emptyList(), SpeakerRegistry.LABELS)
        assertEquals(0.0, request.chunkOffset, 0.0)
        assertEquals(chunk.size, request.audio.size)
        assertTrue(request.prompt().contains("No speakers are known yet"))
    }

    @Test
    fun referencesPrecedeTheChunkAndArePromptedWithTheirTimes() {
        val request = ChunkRequest(chunk, listOf(speaker("S1", 4.0), speaker("S2", 3.0)), listOf("S3", "S4"))
        // 4.0 + 0.4 gap + 3.0 + 0.8 lead-in
        assertEquals(8.2, request.chunkOffset, 1e-9)
        assertEquals(((8.2 + 30) * 16_000).toInt(), request.audio.size)
        val prompt = request.prompt()
        assertTrue(prompt, prompt.contains("0.0s to 4.0s: the voice of S1"))
        assertTrue(prompt, prompt.contains("4.4s to 7.4s: the voice of S2"))
        assertTrue(prompt, prompt.contains("starts at 8.2s"))
        assertTrue(prompt, prompt.contains("S3, S4"))
    }

    @Test
    fun parseShiftsTimesDropsReferenceEchoesAndBadLabels() {
        val request = ChunkRequest(chunk, listOf(speaker("S1", 4.0)), listOf("S2", "S3", "S4"))
        // chunkOffset = 4.0 + 0.8 = 4.8
        val json = """
            {"segments": [
              {"speaker": "S1", "start": 0.5, "end": 3.9, "text": "reference echo"},
              {"speaker": "S2", "start": 10.8, "end": 12.8, "text": "  second  "},
              {"speaker": "S1", "start": 5.0, "end": 7.8, "text": "first"},
              {"speaker": "S9", "start": 6.0, "end": 7.0, "text": "bad label"},
              {"speaker": "S3", "start": 8.0, "end": 9.0, "text": "   "}
            ]}
        """
        val segments = request.parse(json)
        assertEquals(listOf("first", "second"), segments.map { it.text })
        assertEquals(0.2, segments[0].start, 1e-9)
        assertEquals(3.0, segments[0].end, 1e-9)
        assertEquals("S2", segments[1].speaker)
        assertEquals(6.0, segments[1].start, 1e-9)
    }

    @Test
    fun parseClampsToTheChunkAndHandlesEmptyReplies() {
        val request = ChunkRequest(chunk, emptyList(), SpeakerRegistry.LABELS)
        val s = request.parse("""{"segments":[{"speaker":"S1","start":29.0,"end":45.0,"text":"tail"}]}""")
        assertEquals(30.0, s.single().end, 1e-9)
        assertTrue(request.parse("""{"segments":[]}""").isEmpty())
        assertTrue(request.parse("{}").isEmpty())
    }
}
