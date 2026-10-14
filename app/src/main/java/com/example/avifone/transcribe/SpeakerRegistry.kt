package com.example.avifone.transcribe

import com.example.avifone.transcribe.local.SpeakerClusterer

/**
 * Display identity of each speaker label (S1..S4) produced by the on-device clusterer:
 * user-chosen names, colors, and merges ("S3 is really S1"), applied to past and future lines.
 */
class SpeakerRegistry {

    data class Speaker(val label: String, val name: String, val colorIndex: Int)

    private val names = mutableMapOf<String, String>()
    /** Merged-away label -> label it now means. */
    private val aliases = mutableMapOf<String, String>()

    /** Follows merges, so old transcript lines show their current speaker. */
    fun resolve(label: String): String {
        var current = label
        repeat(LABELS.size) { current = aliases[current] ?: return current }
        return current
    }

    fun speaker(label: String): Speaker {
        val target = resolve(label)
        return Speaker(target, names[target] ?: defaultName(target), LABELS.indexOf(target).coerceAtLeast(0))
    }

    fun rename(label: String, name: String) {
        val target = resolve(label)
        val trimmed = name.trim()
        if (trimmed.isEmpty()) names.remove(target) else names[target] = trimmed
    }

    /**
     * Treats [from] as the same person as [into]. Returns the resolved (source, target)
     * labels so the clusterer can merge its voice models too, or null if already the same.
     */
    fun merge(from: String, into: String): Pair<String, String>? {
        val source = resolve(from)
        val target = resolve(into)
        if (source == target) return null
        aliases[source] = target
        names.remove(source)
        return source to target
    }

    companion object {
        val LABELS = SpeakerClusterer.LABELS
        fun defaultName(label: String) = "Speaker ${label.removePrefix("S")}"
    }
}
