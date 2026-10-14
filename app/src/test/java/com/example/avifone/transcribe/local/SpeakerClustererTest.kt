package com.example.avifone.transcribe.local

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class SpeakerClustererTest {

    private val rng = Random(7)
    private fun voice(): FloatArray = FloatArray(64) { rng.nextFloat() * 2 - 1 }
    /** A noisy sample of [base]: high similarity to it, low to other random voices. */
    private fun utterance(base: FloatArray, noise: Float = 0.25f) =
        FloatArray(base.size) { base[it] + (rng.nextFloat() * 2 - 1) * noise }

    @Test
    fun groupsUtterancesByVoice() {
        val (a, b, c) = Triple(voice(), voice(), voice())
        val c1 = SpeakerClusterer()
        val labels = listOf(a, b, a, c, b, a, c).map { c1.assign(utterance(it), 3.0) }
        assertEquals(listOf("S1", "S2", "S1", "S3", "S2", "S1", "S3"), labels)
        assertEquals(3, c1.speakerCount)
    }

    @Test
    fun capsAtFourSpeakersAndUsesTheNearest() {
        val voices = List(5) { voice() }
        val c = SpeakerClusterer()
        voices.take(4).forEach { c.assign(utterance(it), 3.0) }
        assertEquals(4, c.speakerCount)
        // A fifth voice can't open a new slot...
        c.assign(utterance(voices[4]), 3.0)
        assertEquals(4, c.speakerCount)
        // ...and known voices still map back to themselves.
        assertEquals("S2", c.assign(utterance(voices[1]), 3.0))
    }

    @Test
    fun shortUtterancesDoNotMoveTheVoiceModel() {
        val (a, b) = Pair(voice(), voice())
        val c = SpeakerClusterer(threshold = 0.55f)
        c.assign(utterance(a), 3.0)
        // Many short, very noisy blips assigned to S1 must not drag its mean toward noise.
        repeat(50) { c.assign(utterance(a, noise = 0.9f), 0.4) }
        assertEquals("S1", c.assign(utterance(a), 3.0))
        assertEquals("S2", c.assign(utterance(b), 3.0))
    }

    @Test
    fun mergeFoldsVoicesAndNeverReusesTheLabel() {
        val (a, b, d) = Triple(voice(), voice(), voice())
        val c = SpeakerClusterer()
        assertEquals("S1", c.assign(utterance(a), 3.0))
        assertEquals("S2", c.assign(utterance(b), 3.0))
        c.merge("S2", "S1")
        assertEquals(1, c.speakerCount)
        // b now belongs to S1 (its centroid moved toward b), and a new voice gets S3, not S2.
        assertEquals("S3", c.assign(utterance(d), 3.0))
    }
}
