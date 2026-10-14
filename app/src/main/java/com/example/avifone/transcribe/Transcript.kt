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
)

/** Rolling, memory-only transcript of the last [windowMs] of conversation. */
class Transcript(private val windowMs: Long = WINDOW_MS) {
    private val lines = mutableListOf<TranscriptLine>()
    private var nextId = 0L

    val all: List<TranscriptLine> get() = lines.toList()

    fun add(speaker: String, text: String, startMs: Long, endMs: Long) {
        lines += TranscriptLine(nextId++, speaker, text, startMs, endMs)
        lines.sortBy { it.startMs }
    }

    /** Drops lines that ended more than [windowMs] before [nowMs]. Returns true if any were dropped. */
    fun prune(nowMs: Long): Boolean = lines.removeAll { it.endMs < nowMs - windowMs }

    fun clear() = lines.clear()

    /** "[10:42] Priya: text" lines for the summary prompt, with merges and names applied. */
    fun toPromptText(registry: SpeakerRegistry): String {
        val clock = SimpleDateFormat("HH:mm", Locale.US)
        return lines.joinToString("\n") {
            "[${clock.format(Date(it.startMs))}] ${registry.speaker(it.speaker).name}: ${it.text}"
        }
    }

    companion object {
        const val WINDOW_MS = 15 * 60 * 1000L
    }
}
