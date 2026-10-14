package com.example.avifone.transcribe

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Streaming windowed-sinc resampler (Blackman window), used to turn the engine's
 * capture rate (usually 48 kHz) into the 16 kHz mono that speech models expect.
 * Feed it consecutive blocks; it keeps the history needed across block boundaries.
 */
class Resampler(
    val inputRate: Int,
    val outputRate: Int,
    private val halfTaps: Int = 32,
) {
    private val step = inputRate.toDouble() / outputRate
    // Low-pass a little below the lower Nyquist so downsampling doesn't alias.
    private val cutoff = min(1.0, outputRate.toDouble() / inputRate) * 0.92

    private var buffer = FloatArray(4096)
    private var length = halfTaps  // leading zeros act as history for the first outputs
    private var position = halfTaps.toDouble()

    fun process(input: FloatArray, count: Int = input.size): FloatArray {
        if (inputRate == outputRate) return input.copyOf(count)
        append(input, count)

        val out = FloatArray(((length - position) / step).toInt().coerceAtLeast(0) + 1)
        var produced = 0
        while (position + halfTaps < length) {
            out[produced++] = interpolate(position)
            position += step
        }

        // Drop input that no future output can reach.
        val consumed = floor(position).toInt() - halfTaps
        if (consumed > 0) {
            System.arraycopy(buffer, consumed, buffer, 0, length - consumed)
            length -= consumed
            position -= consumed
        }
        return out.copyOf(produced)
    }

    private fun append(input: FloatArray, count: Int) {
        if (length + count > buffer.size) {
            buffer = buffer.copyOf(maxOf(buffer.size * 2, length + count))
        }
        System.arraycopy(input, 0, buffer, length, count)
        length += count
    }

    private fun interpolate(t: Double): Float {
        val center = floor(t).toInt()
        var sum = 0.0
        for (k in center - halfTaps + 1..center + halfTaps) {
            sum += buffer[k] * kernel(t - k)
        }
        return sum.toFloat()
    }

    private fun kernel(distance: Double): Double {
        val x = distance / halfTaps
        if (x <= -1.0 || x >= 1.0) return 0.0
        val window = 0.42 + 0.5 * cos(PI * x) + 0.08 * cos(2 * PI * x)
        val arg = PI * cutoff * distance
        val sinc = if (arg == 0.0) 1.0 else sin(arg) / arg
        return cutoff * sinc * window
    }
}
