package com.example.avifone.transcribe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class ResamplerTest {

    private fun sine(freq: Double, rate: Int, seconds: Double, amp: Float = 0.5f) =
        FloatArray((rate * seconds).toInt()) { (amp * sin(2 * PI * freq * it / rate)).toFloat() }

    private fun rmsDb(x: FloatArray): Float {
        val mean = x.fold(0.0) { acc, v -> acc + v * v } / x.size
        return (10 * kotlin.math.log10(mean.coerceAtLeast(1e-12))).toFloat()
    }

    private fun zeroCrossingFrequency(x: FloatArray, rate: Int): Double {
        var crossings = 0
        for (i in 1 until x.size) if ((x[i - 1] < 0) != (x[i] < 0)) crossings++
        return crossings / 2.0 / (x.size.toDouble() / rate)
    }

    @Test
    fun keepsInBandToneFrequencyAndLevel() {
        val out = Resampler(48_000, 16_000).process(sine(1000.0, 48_000, 1.0))
        assertEquals(16_000.0, out.size.toDouble(), 40.0)
        val steady = out.copyOfRange(400, out.size - 400)
        assertEquals(1000.0, zeroCrossingFrequency(steady, 16_000), 5.0)
        assertEquals(rmsDb(sine(1000.0, 16_000, 1.0)), rmsDb(steady), 0.5f)
    }

    @Test
    fun rejectsToneAboveNewNyquist() {
        // 12 kHz can't exist at 16 kHz; without filtering it would alias to 4 kHz.
        val out = Resampler(48_000, 16_000).process(sine(12_000.0, 48_000, 1.0))
        val steady = out.copyOfRange(400, out.size - 400)
        assertTrue("aliased energy ${rmsDb(steady)} dB", rmsDb(steady) < -50f)
    }

    @Test
    fun streamingInBlocksMatchesOneShot() {
        val input = sine(440.0, 48_000, 0.5)
        val oneShot = Resampler(48_000, 16_000).process(input)
        val streaming = Resampler(48_000, 16_000)
        val pieces = input.toList().chunked(997).map { streaming.process(it.toFloatArray()) }
        val joined = pieces.fold(FloatArray(0)) { acc, p -> acc + p }
        assertArrayEquals(oneShot, joined, 1e-5f)
    }

    @Test
    fun handlesNonIntegerRatio() {
        val out = Resampler(44_100, 16_000).process(sine(1000.0, 44_100, 1.0))
        assertEquals(16_000.0, out.size.toDouble(), 40.0)
        assertEquals(1000.0, zeroCrossingFrequency(out.copyOfRange(400, out.size - 400), 16_000), 5.0)
    }
}
