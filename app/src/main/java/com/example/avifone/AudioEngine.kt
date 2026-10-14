package com.example.avifone

/**
 * Thin wrapper around the native Oboe engine (see src/main/cpp). The engine owns the
 * microphone; headphone pass-through ("listen") and raw-sample capture for
 * transcription ("capture") are independent features of the same input stream.
 */
object AudioEngine {
    init {
        System.loadLibrary("avifone")
    }

    data class Stats(
        val sampleRate: Int,
        val framesPerBurst: Int,
        val outputBufferFrames: Int,
        /** Estimated mic-to-headphone latency, or null if the device can't report it. */
        val latencyMs: Float?,
        val outputXRuns: Int,
        val inputUnderflows: Int,
        val inputLevelDb: Float,
        /** Attenuation currently applied by ambient reduction (<= 0 dB). */
        val reductionDb: Float,
        val mmapInput: Boolean,
        val mmapOutput: Boolean,
        val inputPreset: Int,
        /** Captured samples dropped because the transcription reader fell behind. */
        val captureDropped: Int,
    ) {
        companion object {
            fun fromArray(a: FloatArray) = Stats(
                sampleRate = a[0].toInt(),
                framesPerBurst = a[1].toInt(),
                outputBufferFrames = a[2].toInt(),
                latencyMs = a[3].takeIf { it >= 0f },
                outputXRuns = a[4].toInt(),
                inputUnderflows = a[5].toInt(),
                inputLevelDb = a[6],
                reductionDb = a[7],
                mmapInput = a[8] > 0f,
                mmapOutput = a[9] > 0f,
                inputPreset = a[10].toInt(),
                captureDropped = a[11].toInt(),
            )
        }
    }

    /**
     * Opens, reconfigures or closes the streams so exactly these features run.
     * Returns 0 on success, otherwise a negative oboe::Result code.
     */
    fun setModes(listen: Boolean, capture: Boolean): Int = nativeSetModes(listen, capture)
    /** Copies captured mono samples into [dest]; returns how many. Never blocks. */
    fun readCapture(dest: FloatArray): Int = nativeReadCapture(dest)
    val captureSampleRate: Int get() = nativeCaptureSampleRate()
    val isRunning: Boolean get() = nativeIsRunning()
    fun setVolume(linearGain: Float) = nativeSetVolume(linearGain)
    fun setNoiseReduction(enabled: Boolean, strength: Float) =
        nativeSetNoiseReduction(enabled, strength)
    fun stats(): Stats = Stats.fromArray(nativeGetStats())
    /** True once after the audio route was lost (e.g. headphones unplugged). */
    fun consumeDisconnected(): Boolean = nativeConsumeDisconnected()

    private external fun nativeSetModes(listen: Boolean, capture: Boolean): Int
    private external fun nativeReadCapture(dest: FloatArray): Int
    private external fun nativeCaptureSampleRate(): Int
    private external fun nativeIsRunning(): Boolean
    private external fun nativeSetVolume(gain: Float)
    private external fun nativeSetNoiseReduction(enabled: Boolean, strength: Float)
    private external fun nativeGetStats(): FloatArray
    private external fun nativeConsumeDisconnected(): Boolean
}
