package com.example.avifone.transcribe

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.math.withSign

/** Helpers for the 16 kHz mono float audio that the transcription pipeline works with. */
object AudioClips {
    const val SAMPLE_RATE = 16_000

    /** 16-bit PCM mono WAV. */
    fun toWav(samples: FloatArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val dataBytes = samples.size * 2
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16)
            .putShort(1)                      // PCM
            .putShort(1)                      // mono
            .putInt(sampleRate)
            .putInt(sampleRate * 2)           // byte rate
            .putShort(2)                      // block align
            .putShort(16)                     // bits per sample
        buffer.put("data".toByteArray()).putInt(dataBytes)
        for (s in samples) {
            buffer.putShort((s.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
        }
        return buffer.array()
    }

    /** Joins clips with [gapSeconds] of silence between them. */
    fun concat(clips: List<FloatArray>, gapSeconds: Double, sampleRate: Int = SAMPLE_RATE): FloatArray {
        val gap = (gapSeconds * sampleRate).roundToInt()
        val total = clips.sumOf { it.size } + gap * (clips.size - 1).coerceAtLeast(0)
        val out = FloatArray(total)
        var offset = 0
        clips.forEachIndexed { i, clip ->
            if (i > 0) offset += gap
            clip.copyInto(out, offset)
            offset += clip.size
        }
        return out
    }

    fun rmsDb(samples: FloatArray): Float {
        if (samples.isEmpty()) return -120f
        var sum = 0.0
        for (s in samples) sum += s * s
        return (20 * log10(max(sqrt(sum / samples.size), 1e-6))).toFloat()
    }

    /**
     * Brings speech to a consistent loudness for the model: scales to [targetRmsDb]
     * (boosting by at most [maxGainDb], never attenuating) and soft-limits peaks so
     * the boost can't clip. Phone mics without AGC often capture speech 20-30 dB
     * below what speech models expect.
     */
    fun normalize(samples: FloatArray, targetRmsDb: Float = -20f, maxGainDb: Float = 30f): FloatArray {
        val gainDb = (targetRmsDb - rmsDb(samples)).coerceIn(0f, maxGainDb)
        if (gainDb < 0.5f) return samples
        val gain = 10.0.pow(gainDb / 20.0).toFloat()
        return FloatArray(samples.size) { i ->
            val y = samples[i] * gain
            val a = abs(y)
            if (a <= KNEE) y else (KNEE + (1 - KNEE) * tanh((a - KNEE) / (1 - KNEE))).toFloat().withSign(y)
        }
    }

    private const val KNEE = 0.8f

    fun slice(samples: FloatArray, startSeconds: Double, endSeconds: Double, sampleRate: Int = SAMPLE_RATE): FloatArray {
        val from = (startSeconds * sampleRate).roundToInt().coerceIn(0, samples.size)
        val to = (endSeconds * sampleRate).roundToInt().coerceIn(from, samples.size)
        return samples.copyOfRange(from, to)
    }

    fun seconds(samples: FloatArray, sampleRate: Int = SAMPLE_RATE): Double =
        samples.size.toDouble() / sampleRate
}
