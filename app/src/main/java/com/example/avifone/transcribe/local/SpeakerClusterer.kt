package com.example.avifone.transcribe.local

import kotlin.math.sqrt

/**
 * Online speaker clustering for up to [maxSpeakers] voices.
 *
 * Each utterance's speaker embedding is compared (cosine similarity) with the running
 * mean of every known speaker. A match at or above [threshold] joins that speaker;
 * otherwise a new speaker is started, or — once [maxSpeakers] exist — the closest one
 * is used. Utterances shorter than [minUpdateSeconds] are labelled but don't move a
 * speaker's mean, because short embeddings are noisy.
 *
 * The default threshold comes from the model evaluation: with NeMo SpeakerNet,
 * same-speaker similarity was >= 0.81 and different-speaker <= 0.35.
 */
class SpeakerClusterer(
    private val threshold: Float = 0.55f,
    private val maxSpeakers: Int = LABELS.size,
    private val minUpdateSeconds: Double = 1.0,
) {
    private class Speaker(val label: String, val mean: FloatArray, var count: Int)

    private val speakers = mutableListOf<Speaker>()
    /** Labels ever handed out, so a merged-away label is never given to a new voice. */
    private val usedLabels = mutableSetOf<String>()

    val speakerCount: Int get() = speakers.size

    fun assign(embedding: FloatArray, durationSeconds: Double): String {
        val e = normalized(embedding)
        val best = speakers.maxByOrNull { cosine(e, it.mean) }
        val similarity = best?.let { cosine(e, it.mean) } ?: -1f
        val nextLabel = LABELS.firstOrNull { it !in usedLabels }
        val speaker = when {
            best != null && similarity >= threshold -> best
            speakers.size < maxSpeakers && nextLabel != null ->
                return Speaker(nextLabel, e.copyOf(), 1).also {
                    speakers += it
                    usedLabels += nextLabel
                }.label
            else -> best ?: return LABELS.first()
        }
        if (durationSeconds >= minUpdateSeconds) {
            for (i in speaker.mean.indices) {
                speaker.mean[i] = (speaker.mean[i] * speaker.count + e[i]) / (speaker.count + 1)
            }
            speaker.count++
        }
        return speaker.label
    }

    /** Folds [from] into [into]: future speech that sounds like either goes to [into]. */
    fun merge(from: String, into: String) {
        if (from == into) return
        val source = speakers.firstOrNull { it.label == from } ?: return
        val target = speakers.firstOrNull { it.label == into } ?: return
        val total = source.count + target.count
        for (i in target.mean.indices) {
            target.mean[i] = (target.mean[i] * target.count + source.mean[i] * source.count) / total
        }
        target.count = total
        speakers.remove(source)
    }

    fun reset() {
        speakers.clear()
        usedLabels.clear()
    }

    private fun normalized(v: FloatArray): FloatArray {
        val norm = sqrt(v.fold(0f) { acc, x -> acc + x * x }).coerceAtLeast(1e-9f)
        return FloatArray(v.size) { v[it] / norm }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            nb += b[i] * b[i]
        }
        return dot / sqrt(nb).coerceAtLeast(1e-9f)  // `a` is already unit length
    }

    companion object {
        val LABELS = listOf("S1", "S2", "S3", "S4")
    }
}
