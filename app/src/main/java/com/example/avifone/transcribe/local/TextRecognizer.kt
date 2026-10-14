package com.example.avifone.transcribe.local

import android.content.res.AssetManager
import com.example.avifone.transcribe.speech.SpeechLanguage
import com.example.avifone.transcribe.speech.SpeechModel
import com.example.avifone.transcribe.speech.SpeechModelKind
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * Turns one utterance (16 kHz mono, already cut by the VAD) into text. Implementations wrap a
 * sherpa-onnx recognizer; none is thread-safe, so [LocalTranscriber] calls them from the speech
 * thread only. Loading can take seconds for the larger models.
 */
interface TextRecognizer : AutoCloseable {
    /** The catalogue id of the model behind this recognizer. */
    val modelId: String

    /**
     * Audio from just before the VAD's cut that should be prepended to each segment. Streaming
     * models drop the first word without it; Moonshine does worse with it.
     */
    val preRollSeconds: Float

    /** Longest segment this model decodes correctly; longer ones are decoded in pieces. */
    val maxSegmentSeconds: Float

    /** Text for the segment, trimmed; "" when nothing was recognised. */
    fun transcribe(samples: FloatArray): String

    companion object {
        /** Builds the recognizer for [model]; downloadable models read from [dir]. */
        fun create(model: SpeechModel, assets: AssetManager, dir: File, language: SpeechLanguage): TextRecognizer =
            when (model.kind) {
                SpeechModelKind.MOONSHINE -> MoonshineRecognizer(assets, model.id)
                SpeechModelKind.WHISPER -> WhisperRecognizer(dir, model.id, language)
                SpeechModelKind.NEMOTRON -> NemotronRecognizer(dir, model.id, language)
            }

        /** Threads for the big models: the large cores, without starving the audio path. */
        internal val bigModelThreads: Int get() = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    }
}

/** Moonshine tiny English v2, bundled in the APK under assets/models/moonshine. */
class MoonshineRecognizer(assets: AssetManager, override val modelId: String) : TextRecognizer {
    override val preRollSeconds = 0f
    /**
     * sherpa-onnx 1.13.8 returns nothing beyond ~9.2 s (see LocalTranscriber), and on the
     * synthetic set 8-9 s segments already decode worse whole than in two halves.
     */
    override val maxSegmentSeconds = LocalTranscriber.MAX_SEGMENT_SECONDS
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

    override fun transcribe(samples: FloatArray): String = recognizer.decodeOnce(samples)
    override fun close() = recognizer.release()
}

/** Whisper (small or large-v3-turbo), int8, from files in [dir]. Segments must stay under 30 s. */
class WhisperRecognizer(dir: File, override val modelId: String, language: SpeechLanguage) : TextRecognizer {
    /** 0.5 s cut WER from 8.8 % to 6.9 % on the synthetic set (desktop; not yet checked on a phone). */
    override val preRollSeconds = 0.5f
    override val maxSegmentSeconds = 28f  // Whisper's window is 30 s
    private val recognizer = OfflineRecognizer(
        null,
        OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = File(dir, "encoder.int8.onnx").path,
                    decoder = File(dir, "decoder.int8.onnx").path,
                    // "" lets the model detect the language of each utterance.
                    language = if (language == SpeechLanguage.AUTO) "" else language.code,
                    task = "transcribe",
                ),
                tokens = File(dir, "tokens.txt").path,
                numThreads = TextRecognizer.bigModelThreads,
            ),
        ),
    )

    override fun transcribe(samples: FloatArray): String = recognizer.decodeOnce(samples)
    override fun close() = recognizer.release()
}

/**
 * NVIDIA Nemotron 3.5 streaming transducer, used one utterance at a time: the stream gets the
 * whole segment plus a tail of silence so the last 560 ms chunk is decoded, then is discarded.
 * Streaming partial results are a possible later addition.
 */
class NemotronRecognizer(dir: File, override val modelId: String, private val language: SpeechLanguage) : TextRecognizer {
    /** With the silent lead-in below, real pre-roll added nothing (6.1 % vs 7.3 % WER with it). */
    override val preRollSeconds = 0f
    override val maxSegmentSeconds = 60f
    private val recognizer = OnlineRecognizer(
        null,
        OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(dir, "encoder.int8.onnx").path,
                    decoder = File(dir, "decoder.int8.onnx").path,
                    joiner = File(dir, "joiner.int8.onnx").path,
                ),
                tokens = File(dir, "tokens.txt").path,
                numThreads = TextRecognizer.bigModelThreads,
            ),
            enableEndpoint = false,  // the VAD already decides where utterances end
        ),
    )
    private val lead = FloatArray((LEAD_SECONDS * LocalTranscriber.SAMPLE_RATE).toInt())
    private val tail = FloatArray((TAIL_SECONDS * LocalTranscriber.SAMPLE_RATE).toInt())

    override fun transcribe(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.setOption("language", language.code)  // "auto" detects per utterance
            stream.acceptWaveform(lead, LocalTranscriber.SAMPLE_RATE)
            stream.acceptWaveform(samples, LocalTranscriber.SAMPLE_RATE)
            stream.acceptWaveform(tail, LocalTranscriber.SAMPLE_RATE)
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()

    private companion object {
        /**
         * Silence before the segment: the streaming encoder needs warm-up or it drops the first
         * word. On the synthetic set (tools/eval_asr.py) WER was 11.1 % without and 6.1 % with it.
         */
        const val LEAD_SECONDS = 1.0f
        /** Longer than one 560 ms chunk plus the encoder's right context. */
        const val TAIL_SECONDS = 0.8f
    }
}

private fun OfflineRecognizer.decodeOnce(samples: FloatArray): String = createStream().let { stream ->
    try {
        stream.acceptWaveform(samples, LocalTranscriber.SAMPLE_RATE)
        decode(stream)
        getResult(stream).text.trim()
    } finally {
        stream.release()
    }
}
