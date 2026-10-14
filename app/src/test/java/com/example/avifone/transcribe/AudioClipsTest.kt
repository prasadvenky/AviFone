package com.example.avifone.transcribe

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioClipsTest {

    @Test
    fun wavHeaderDescribes16kMonoPcm() {
        val wav = AudioClips.toWav(FloatArray(1600) { 0.25f })
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(1, b.getShort(20).toInt())        // PCM
        assertEquals(1, b.getShort(22).toInt())        // mono
        assertEquals(16_000, b.getInt(24))
        assertEquals(16, b.getShort(34).toInt())       // bits
        assertEquals(3200, b.getInt(40))               // data bytes
        assertEquals(44 + 3200, wav.size)
        assertEquals(8192, b.getShort(44).toInt())     // 0.25 * 32767, rounded
    }

    @Test
    fun wavClipsOutOfRangeSamples() {
        val b = ByteBuffer.wrap(AudioClips.toWav(floatArrayOf(2f, -2f))).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(32767, b.getShort(44).toInt())
        assertEquals(-32767, b.getShort(46).toInt())
    }

    @Test
    fun concatInsertsGapsBetweenClipsOnly() {
        val out = AudioClips.concat(listOf(FloatArray(100) { 1f }, FloatArray(50) { 1f }), gapSeconds = 0.01)
        assertEquals(100 + 160 + 50, out.size)
        assertEquals(0f, out[150])
        assertEquals(1f, out[260])
    }

    @Test
    fun normalizeBoostsQuietSpeechToTargetWithoutClipping() {
        val quiet = FloatArray(16_000) { (0.01 * kotlin.math.sin(2 * Math.PI * 200 * it / 16_000)).toFloat() }
        val out = AudioClips.normalize(quiet)
        assertEquals(-20f, AudioClips.rmsDb(out), 0.5f)
        assertEquals(true, out.all { kotlin.math.abs(it) < 1f })
    }

    @Test
    fun normalizeCapsGainAndNeverAttenuates() {
        val veryQuiet = FloatArray(16_000) { (0.0001 * kotlin.math.sin(2 * Math.PI * 200 * it / 16_000)).toFloat() }
        val boosted = AudioClips.normalize(veryQuiet)
        assertEquals(AudioClips.rmsDb(veryQuiet) + 30f, AudioClips.rmsDb(boosted), 0.5f)
        val loud = FloatArray(16_000) { (0.5 * kotlin.math.sin(2 * Math.PI * 200 * it / 16_000)).toFloat() }
        assertEquals(AudioClips.rmsDb(loud), AudioClips.rmsDb(AudioClips.normalize(loud)), 0.01f)
    }

    @Test
    fun rmsOfFullScaleSineIsAboutMinus3Db() {
        val s = FloatArray(16_000) { kotlin.math.sin(2 * Math.PI * 100 * it / 16_000).toFloat() }
        assertEquals(-3.01f, AudioClips.rmsDb(s), 0.05f)
        assertEquals(-120f, AudioClips.rmsDb(FloatArray(0)))
    }
}
