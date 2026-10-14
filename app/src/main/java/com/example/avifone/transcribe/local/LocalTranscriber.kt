package com.example.avifone.transcribe.local

import android.content.res.AssetManager
import android.util.Log
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
                threshold = 0.4f,  // 0.5 missed quiet and distant speakers
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.15f,  // keep short replies ("yes", "no")
                windowSize = WINDOW,
                // sherpa-onnx 1.13.8 silently returns empty text for Moonshine v2 segments
                // longer than ~9.2 s (k2-fsa/sherpa-onnx#3471; fixed on master by #3976 but in
                // no release yet). Keep every segment under that until the AAR is updated.
                maxSpeechDuration = MAX_SEGMENT_SECONDS,
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
     * Marks the start of a session: drops the partial window left over from the previous one
     * and returns the running sample position the new session's timestamps anchor to. The VAD
     * itself is not reset, because that would restart its sample counter.
     */
    fun startSession(): Long {
        windowFill = 0
        return samplesAccepted
    }

    /** Ends the session: finishes whatever utterance is in progress. */
    fun flush(): List<LocalUtterance> {
        val done = mutableListOf<LocalUtterance>()
        if (windowFill > 0) {  // the VAD only takes whole windows; pad the last one
            window.fill(0f, windowFill)
            samplesAccepted += WINDOW - windowFill
            windowFill = 0
            vad.acceptWaveform(window.copyOf())
            drain(done)
        }
        vad.flush()
        drain(done)
        return done
    }

    private fun drain(out: MutableList<LocalUtterance>) {
        while (!vad.empty()) {
            val segment = vad.front()  // returns a copy, safe to use after pop()
            vad.pop()
            transcribe(segment.samples, segment.start.toLong())?.let(out::add)
        }
    }

    /**
     * Text for one segment of any length. The VAD's maxSpeechDuration is a soft target (it only
     * lowers the pause it needs to cut), so the recognizer's limit is enforced here: an over-long
     * segment is decoded in equal pieces and the text joined.
     */
    internal fun recognizeText(samples: FloatArray): String {
        val maxSamples = (MAX_SEGMENT_SECONDS * SAMPLE_RATE).toInt()
        val pieces = (samples.size + maxSamples - 1) / maxSamples
        if (pieces <= 1) return recognize(samples)
        val pieceSize = (samples.size + pieces - 1) / pieces
        return (0 until pieces).joinToString(" ") { i ->
            recognize(samples.copyOfRange(i * pieceSize, minOf((i + 1) * pieceSize, samples.size)))
        }.trim()
    }

    private fun transcribe(samples: FloatArray, start: Long): LocalUtterance? {
        val text = recognizeText(samples)  // the speaker embedding below uses the whole segment
        val seconds = samples.size.toDouble() / SAMPLE_RATE
        if (text.none { it.isLetterOrDigit() }) {
            // A second or more of detected speech with no words is the signature of a decode
            // failure rather than a cough, so leave a trace in the log.
            if (seconds >= 1.0) Log.w(TAG, "Recognizer returned nothing for a %.1f s segment".format(seconds))
            return null
        }

        val embedding = embeddings.createStream().let { stream ->
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                stream.inputFinished()
                if (embeddings.isReady(stream)) embeddings.compute(stream) else null
            } finally {
                stream.release()
            }
        }
        val speaker = embedding?.let { clusterer.assign(it, seconds) } ?: SpeakerClusterer.LABELS.first()
        return LocalUtterance(speaker, text, start, start + samples.size)
    }

    private fun recognize(samples: FloatArray): String = recognizer.createStream().let { stream ->
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    override fun close() {
        vad.release()
        recognizer.release()
        embeddings.release()
    }

    companion object {
        private const val TAG = "LocalSpeech"
        const val SAMPLE_RATE = 16_000
        /** Longest utterance handed to the recognizer; the VAD force-splits beyond this. */
        const val MAX_SEGMENT_SECONDS = 8f
        private const val WINDOW = 512
    }
}
