package com.example.avifone.transcribe.local

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/** One finished utterance. Sample positions count 16 kHz samples since the session began. */
data class LocalUtterance(val speaker: String, val text: String, val startSample: Long, val endSample: Long)

/**
 * On-device speech-to-text with speaker labels (sherpa-onnx, models bundled in assets/models):
 *
 *   16 kHz audio -> Silero VAD (cuts utterances at pauses)
 *                -> Moonshine tiny (text)  +  NeMo SpeakerNet (voice embedding)
 *                -> SpeakerClusterer (S1..S4)
 *
 * Not thread-safe: create and use it from one thread. Loading takes a second or two.
 */
class LocalTranscriber(assets: AssetManager, val clusterer: SpeakerClusterer = SpeakerClusterer()) : AutoCloseable {

    private val vad = Vad(
        assets,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "models/silero_vad.onnx",
                threshold = 0.5f,
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.25f,
                windowSize = WINDOW,
                maxSpeechDuration = 15f,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        ),
    )

    private val recognizer = OfflineRecognizer(
        assets,
        OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    encoder = "models/moonshine/encoder_model.ort",
                    mergedDecoder = "models/moonshine/decoder_model_merged.ort",
                ),
                tokens = "models/moonshine/tokens.txt",
                numThreads = 2,
            ),
        ),
    )

    private val embeddings = SpeakerEmbeddingExtractor(
        assets,
        SpeakerEmbeddingExtractorConfig(model = "models/nemo_en_speakerverification_speakernet.onnx", numThreads = 2),
    )

    // The VAD consumes fixed windows; carry the remainder between calls.
    private val window = FloatArray(WINDOW)
    private var windowFill = 0

    /** Total samples fed so far; utterance positions are on this same running count. */
    var samplesAccepted = 0L
        private set

    val isSpeechDetected: Boolean get() = vad.isSpeechDetected()

    /** Feeds 16 kHz mono audio; returns any utterances that finished. */
    fun accept(samples: FloatArray, count: Int = samples.size): List<LocalUtterance> {
        val done = mutableListOf<LocalUtterance>()
        samplesAccepted += count
        var offset = 0
        while (offset < count) {
            val n = minOf(count - offset, WINDOW - windowFill)
            samples.copyInto(window, windowFill, offset, offset + n)
            windowFill += n
            offset += n
            if (windowFill == WINDOW) {
                vad.acceptWaveform(window.copyOf())
                windowFill = 0
                drain(done)
            }
        }
        return done
    }

    /**
     * Ends the session: finishes whatever utterance is in progress. The Kotlin Vad has no
     * flush(), so feed silence longer than its end-of-speech pause to close the segment.
     */
    fun flush(): List<LocalUtterance> = accept(FloatArray(WINDOW * FLUSH_WINDOWS))

    private fun drain(out: MutableList<LocalUtterance>) {
        while (!vad.empty()) {
            val segment = vad.front()  // returns a copy, safe to use after pop()
            vad.pop()
            transcribe(segment.samples, segment.start.toLong())?.let(out::add)
        }
    }

    private fun transcribe(samples: FloatArray, start: Long): LocalUtterance? {
        val text = recognizer.createStream().let { stream ->
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                recognizer.decode(stream)
                recognizer.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        }
        if (text.none { it.isLetterOrDigit() }) return null

        val embedding = embeddings.createStream().let { stream ->
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                stream.inputFinished()
                if (embeddings.isReady(stream)) embeddings.compute(stream) else null
            } finally {
                stream.release()
            }
        }
        val seconds = samples.size.toDouble() / SAMPLE_RATE
        val speaker = embedding?.let { clusterer.assign(it, seconds) } ?: SpeakerClusterer.LABELS.first()
        return LocalUtterance(speaker, text, start, start + samples.size)
    }

    override fun close() {
        vad.release()
        recognizer.release()
        embeddings.release()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val WINDOW = 512
        private const val FLUSH_WINDOWS = 25  // 0.8 s, > minSilenceDuration
    }
}
