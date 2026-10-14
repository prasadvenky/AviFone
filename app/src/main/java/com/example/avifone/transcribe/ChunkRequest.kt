package com.example.avifone.transcribe

import org.json.JSONObject
import java.util.Locale

/** One utterance, with times in seconds relative to the start of its chunk. */
data class ChunkSegment(val speaker: String, val start: Double, val end: Double, val text: String)

/**
 * Builds a single transcription request for one chunk of conversation and parses the
 * reply. Gemini accepts one audio file per request, so the audio is laid out as:
 *
 *   [S1 reference] gap [S2 reference] gap ... lead-in silence [chunk to transcribe]
 *
 * and the prompt says exactly which time ranges are references. Segments the model
 * returns for the reference region are dropped, and the rest are shifted back to
 * chunk-relative time.
 */
class ChunkRequest(
    val chunk: FloatArray,
    references: List<SpeakerRegistry.Speaker>,
    private val freeLabels: List<String>,
) {
    private val refs = references.filter { it.clip != null }
    private val refRanges: List<Pair<String, ClosedFloatingPointRange<Double>>>
    /** Where the chunk starts inside the combined audio, in seconds. */
    val chunkOffset: Double
    val audio: FloatArray

    init {
        val ranges = mutableListOf<Pair<String, ClosedFloatingPointRange<Double>>>()
        var t = 0.0
        refs.forEachIndexed { i, speaker ->
            if (i > 0) t += REF_GAP_SECONDS
            val length = AudioClips.seconds(speaker.clip!!)
            ranges += speaker.label to (t..t + length)
            t += length
        }
        refRanges = ranges
        // Each part is normalized on its own, so a quiet speaker's reference clip is as
        // clear to the model as a loud one's.
        val parts = refs.map { AudioClips.normalize(it.clip!!) }
        val body = AudioClips.normalize(chunk)
        chunkOffset = if (parts.isEmpty()) 0.0 else t + LEAD_IN_SECONDS
        audio = if (parts.isEmpty()) body else {
            val prefix = AudioClips.concat(parts, REF_GAP_SECONDS)
            AudioClips.concat(listOf(prefix, body), LEAD_IN_SECONDS)
        }
    }

    fun wav(): ByteArray = AudioClips.toWav(audio)

    fun prompt(): String = buildString {
        appendLine("Transcribe the conversation in this audio and identify who is speaking.")
        appendLine()
        if (refRanges.isEmpty()) {
            appendLine("No speakers are known yet. Label speakers S1, S2, S3, S4 in order of first appearance.")
        } else {
            appendLine("The audio starts with reference clips of speakers you already know:")
            for ((label, range) in refRanges) {
                appendLine("- ${fmt(range.start)}s to ${fmt(range.endInclusive)}s: the voice of $label")
            }
            appendLine("The conversation to transcribe starts at ${fmt(chunkOffset)}s.")
            appendLine()
            appendLine("Give each utterance the label of the reference voice it matches.")
            if (freeLabels.isEmpty()) {
                appendLine("Every voice belongs to one of these known speakers; pick the closest match.")
            } else {
                appendLine("A voice that matches none of the references is a new speaker: use the first unused label from ${freeLabels.joinToString(", ")}.")
            }
            appendLine("Do NOT transcribe the reference clips themselves.")
        }
        appendLine()
        appendLine("Rules:")
        appendLine("- There are at most 4 different speakers.")
        appendLine("- Start a new segment whenever the speaker changes, and at sentence ends in long turns.")
        appendLine("- start and end are in seconds from the very beginning of the audio.")
        appendLine("- Write exactly what is said, in the language spoken. Don't translate or summarize.")
        appendLine("- Leave out filler noises. If nothing intelligible is said, return an empty segments list.")
    }

    /** Parses the model's JSON and maps segment times back to the chunk. */
    fun parse(json: String): List<ChunkSegment> {
        val segments = JSONObject(json.trim()).optJSONArray("segments") ?: return emptyList()
        val chunkSeconds = AudioClips.seconds(chunk)
        return (0 until segments.length()).mapNotNull { i ->
            val o = segments.getJSONObject(i)
            val text = o.optString("text").trim()
            val speaker = o.optString("speaker")
            val start = o.optDouble("start", Double.NaN)
            val end = o.optDouble("end", Double.NaN)
            when {
                text.isEmpty() || speaker !in SpeakerRegistry.LABELS -> null
                // Missing times: keep the text, place it at the chunk start.
                start.isNaN() || end.isNaN() -> ChunkSegment(speaker, 0.0, 0.0, text)
                // Entirely inside the reference region: an echo of a reference clip.
                end <= chunkOffset + 0.2 -> null
                else -> ChunkSegment(
                    speaker = speaker,
                    start = (start - chunkOffset).coerceIn(0.0, chunkSeconds),
                    end = (end - chunkOffset).coerceIn(0.0, chunkSeconds),
                    text = text,
                )
            }
        }.sortedBy { it.start }
    }

    private fun fmt(seconds: Double) = String.format(Locale.US, "%.1f", seconds)

    companion object {
        const val REF_GAP_SECONDS = 0.4
        const val LEAD_IN_SECONDS = 0.8
    }
}
