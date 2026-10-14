package com.example.avifone.transcribe

/**
 * Keeps speaker identities stable across transcription requests.
 *
 * Gemini labels speakers independently in every request, so each request starts with
 * a short reference clip of every known speaker (see [ChunkRequest]). This registry
 * owns those clips, the user-chosen display names, and merges ("S3 is really S1").
 */
class SpeakerRegistry {

    data class Speaker(
        val label: String,
        val name: String,
        val colorIndex: Int,
        /** 16 kHz reference clip of this speaker's voice, or null until one is found. */
        val clip: FloatArray? = null,
    )

    private val speakers = linkedMapOf<String, Speaker>()
    /** Merged-away label -> label it now means. */
    private val aliases = mutableMapOf<String, String>()

    /** Known speakers that have a reference clip, in label order. */
    fun referenceSpeakers(): List<Speaker> =
        speakers.values.filter { it.clip != null }.sortedBy { it.label }

    /**
     * Labels Gemini may give a voice that matches none of the references: never-used
     * labels first, then speakers seen before who don't have a clean clip yet.
     */
    fun freeLabels(): List<String> {
        val candidates = LABELS.filter { it !in aliases && speakers[it]?.clip == null }
        return candidates.filter { it !in speakers } + candidates.filter { it in speakers }
    }

    /** Follows merges, so old transcript lines show their current speaker. */
    fun resolve(label: String): String {
        var current = label
        repeat(LABELS.size) { current = aliases[current] ?: return current }
        return current
    }

    fun speaker(label: String): Speaker = speakers[resolve(label)] ?: newSpeaker(resolve(label))

    fun all(): List<Speaker> = speakers.values.sortedBy { it.label }

    fun rename(label: String, name: String) {
        val target = resolve(label)
        val trimmed = name.trim()
        speakers[target] = speaker(target).copy(name = trimmed.ifEmpty { defaultName(target) })
    }

    /** Treats [from] as the same person as [into] from now on, for past and future lines. */
    fun merge(from: String, into: String) {
        val source = resolve(from)
        val target = resolve(into)
        if (source == target) return
        aliases[source] = target
        speakers.remove(source)
    }

    /**
     * Registers the speakers found in a chunk and refreshes their reference clips from it:
     * each speaker's longest stretch that doesn't overlap anyone else (at least
     * [MIN_CLIP_SECONDS]) replaces their clip if it's longer than the current one.
     */
    fun learn(chunk: FloatArray, segments: List<ChunkSegment>, sampleRate: Int = AudioClips.SAMPLE_RATE) {
        for ((label, own) in segments.groupBy { resolve(it.speaker) }) {
            val existing = speaker(label)
            val best = own
                .filter { seg -> segments.none { it !== seg && resolve(it.speaker) != label && overlaps(it, seg) } }
                .maxByOrNull { it.end - it.start }
            val duration = best?.let { it.end - it.start } ?: 0.0
            val currentDuration = existing.clip?.let { it.size.toDouble() / sampleRate } ?: 0.0
            val clip = if (best != null && duration >= MIN_CLIP_SECONDS && duration > currentDuration + 0.25 &&
                currentDuration < MAX_CLIP_SECONDS
            ) {
                // Keep the middle of the segment: edges are where speakers hand over.
                val usable = minOf(duration, MAX_CLIP_SECONDS)
                val mid = (best.start + best.end) / 2
                AudioClips.slice(chunk, mid - usable / 2, mid + usable / 2, sampleRate)
            } else {
                existing.clip
            }
            speakers[label] = existing.copy(clip = clip)
        }
    }

    private fun newSpeaker(label: String) = Speaker(
        label = label,
        name = defaultName(label),
        colorIndex = LABELS.indexOf(label).coerceAtLeast(0),
    ).also { speakers[label] = it }

    private fun overlaps(a: ChunkSegment, b: ChunkSegment) = a.start < b.end && b.start < a.end

    companion object {
        val LABELS = listOf("S1", "S2", "S3", "S4")
        const val MIN_CLIP_SECONDS = 2.0
        const val MAX_CLIP_SECONDS = 5.0
        fun defaultName(label: String) = "Speaker ${label.removePrefix("S")}"
    }
}
