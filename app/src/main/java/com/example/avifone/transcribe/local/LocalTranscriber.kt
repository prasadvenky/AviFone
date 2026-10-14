package com.example.avifone.transcribe.local

import android.content.res.AssetManager
import android.util.Log
import com.example.avifone.transcribe.speech.SpeechModelCatalog
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/** One finished utterance. Sample positions count 16 kHz samples since the session began. */
data class LocalUtterance(val speaker: String, val text: String, val startSample: Long, val endSample: Long)

/**
 * On-device speech-to-text with speaker labels (sherpa-onnx):
 *
 *   16 kHz audio -> Silero VAD (cuts utterances at pauses)
 *                -> [TextRecognizer] (text)  +  NeMo SpeakerNet (voice embedding)
 *                -> SpeakerClusterer (S1..S4)
 *
 * The VAD and SpeakerNet are bundled in assets/models; the recognizer is whichever model the
 * user chose (built-in Moonshine tiny, or a downloaded one) and can be swapped between
 * utterances with [setRecognizer]. Not thread-safe: create and use it from one thread.
 */
class LocalTranscriber(
    assets: AssetManager,
    recognizer: TextRecognizer = MoonshineRecognizer(assets, SpeechModelCatalog.MOONSHINE_TINY.id),
    val clusterer: SpeakerClusterer = SpeakerClusterer(),
) : AutoCloseable {

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

    var recognizer: TextRecognizer = recognizer
        private set

    private val embeddings = SpeakerEmbeddingExtractor(
        assets,
        SpeakerEmbeddingExtractorConfig(model = "models/nemo_en_speakerverification_speakernet.onnx", numThreads = 2),
    )

    // The VAD consumes fixed windows; carry the remainder between calls.
    private val window = FloatArray(WINDOW)
    private var windowFill = 0

    // The last few seconds given to the VAD, so a segment can start a little before the
    // detector's cut (TextRecognizer.preRollSeconds). Segment positions count these samples.
    private val history = FloatArray(HISTORY_SECONDS * SAMPLE_RATE)
    private var fedToVad = 0L
    private var lastSegmentEnd = 0L  // pre-roll never reaches into the previous utterance

    /** Total samples fed so far; utterance positions are on this same running count. */
    var samplesAccepted = 0L
        private set

    val isSpeechDetected: Boolean get() = vad.isSpeechDetected()

    /** Replaces the text model; the old one is closed. Takes effect from the next utterance. */
    fun setRecognizer(next: TextRecognizer) {
        if (next === recognizer) return
        val old = recognizer
        recognizer = next
        old.close()
    }

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
                feedWindow()
                drain(done)
            }
        }
        return done
    }

    private fun feedWindow() {
        vad.acceptWaveform(window.copyOf())
        val at = (fedToVad % history.size).toInt()
        val first = minOf(WINDOW, history.size - at)
        window.copyInto(history, at, 0, first)
        if (first < WINDOW) window.copyInto(history, 0, first, WINDOW)
        fedToVad += WINDOW
        windowFill = 0
    }

    /** [segment] with up to [preRollSeconds] of the audio that preceded it. */
    private fun withPreRoll(segment: FloatArray, start: Long, preRollSeconds: Float): FloatArray {
        val from = maxOf(start - (preRollSeconds * SAMPLE_RATE).toLong(), fedToVad - history.size, lastSegmentEnd)
        val n = (start - from).toInt()
        if (n <= 0) return segment
        val out = FloatArray(n + segment.size)
        for (i in 0 until n) out[i] = history[((from + i) % history.size).toInt()]
        segment.copyInto(out, n)
        return out
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
            feedWindow()
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
            // The VAD occasionally emits a degenerate (empty or tiny) segment after logging
            // "Invalid n"; recognizers can't use it and Whisper crashes on empty input.
            if (segment.samples.size < MIN_SEGMENT_SAMPLES) continue
            val start = segment.start.toLong()
            val samples = withPreRoll(segment.samples, start, recognizer.preRollSeconds)
            lastSegmentEnd = start + segment.samples.size
            transcribe(samples, start, segment.samples.size)?.let(out::add)
        }
    }

    /**
     * Text for one segment of any length. The VAD's maxSpeechDuration is a soft target (it only
     * lowers the pause it needs to cut), so the recognizer's own limit is enforced here: an
     * over-long segment is decoded in equal pieces and the text joined.
     */
    internal fun recognizeText(samples: FloatArray): String {
        if (samples.size < MIN_SEGMENT_SAMPLES) return ""
        val maxSamples = (recognizer.maxSegmentSeconds * SAMPLE_RATE).toInt()
        val pieces = (samples.size + maxSamples - 1) / maxSamples
        if (pieces <= 1) return recognizer.transcribe(samples)
        val pieceSize = (samples.size + pieces - 1) / pieces
        return (0 until pieces).joinToString(" ") { i ->
            recognizer.transcribe(samples.copyOfRange(i * pieceSize, minOf((i + 1) * pieceSize, samples.size)))
        }.trim()
    }

    /** [samples] may carry pre-roll; [length] is the detected speech, which the timestamps cover. */
    private fun transcribe(samples: FloatArray, start: Long, length: Int): LocalUtterance? {
        val text = recognizeText(samples)
        val seconds = length.toDouble() / SAMPLE_RATE
        if (text.none { it.isLetterOrDigit() }) {
            // A second or more of detected speech with no words is the signature of a decode
            // failure rather than a cough, so leave a trace in the log.
            if (seconds >= 1.0) Log.w(TAG, "${recognizer.modelId} returned nothing for a %.1f s segment".format(seconds))
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
        return LocalUtterance(speaker, text, start, start + length)
    }

    override fun close() {
        vad.release()
        recognizer.close()
        embeddings.release()
    }

    companion object {
        private const val TAG = "LocalSpeech"
        const val SAMPLE_RATE = 16_000
        /** Utterance length the VAD aims for; recognizers enforce their own hard limits. */
        const val MAX_SEGMENT_SECONDS = 8f
        private const val WINDOW = 512
        /** 0.1 s: shorter than any word; guards the recognizers against degenerate segments. */
        private const val MIN_SEGMENT_SAMPLES = SAMPLE_RATE / 10
        /** Must cover a maximal segment plus its trailing pause plus the longest pre-roll. */
        private const val HISTORY_SECONDS = 12
    }
}
