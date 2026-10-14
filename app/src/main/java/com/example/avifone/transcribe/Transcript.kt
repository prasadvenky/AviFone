package com.example.avifone.transcribe

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A transcript row. Times are wall-clock epoch milliseconds. */
data class TranscriptLine(
    val id: Long,
    val speaker: String,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    /** A stretch of audio that couldn't be transcribed (shown as a gap row). */
    val isGap: Boolean = false,
)

/** Rolling, memory-only transcript of the last [windowMs] of conversation. */
class Transcript(private val windowMs: Long = WINDOW_MS) {
    private val lines = mutableListOf<TranscriptLine>()
    private var nextId = 0L

    val all: List<TranscriptLine> get() = lines.toList()

    fun addChunk(chunkStartMs: Long, segments: List<ChunkSegment>) {
        for (s in segments) {
            lines += TranscriptLine(
                id = nextId++,
                speaker = s.speaker,
                text = s.text,
                startMs = chunkStartMs + (s.start * 1000).toLong(),
                endMs = chunkStartMs + (s.end * 1000).toLong(),
            )
        }
        lines.sortBy { it.startMs }
    }

    fun addGap(startMs: Long, endMs: Long) {
        lines += TranscriptLine(nextId++, speaker = "", text = "", startMs = startMs, endMs = endMs, isGap = true)
        lines.sortBy { it.startMs }
    }

    /** Drops lines that ended more than [windowMs] before [nowMs]. Returns true if any were dropped. */
    fun prune(nowMs: Long): Boolean = lines.removeAll { it.endMs < nowMs - windowMs }

    fun clear() = lines.clear()

    /** "[10:42] Priya: text" lines for the summary prompt, with merges and names applied. */
    fun toPromptText(registry: SpeakerRegistry): String {
        val clock = SimpleDateFormat("HH:mm", Locale.US)
        return lines.filter { !it.isGap }.joinToString("\n") {
            "[${clock.format(Date(it.startMs))}] ${registry.speaker(it.speaker).name}: ${it.text}"
        }
    }

    companion object {
        const val WINDOW_MS = 15 * 60 * 1000L
    }
}
