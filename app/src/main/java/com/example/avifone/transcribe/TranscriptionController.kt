package com.example.avifone.transcribe

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.util.Log
import com.example.avifone.AudioEngine
import com.example.avifone.ListenController
import androidx.core.content.edit
import com.google.firebase.ai.type.QuotaExceededException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SpeakerInfo(val label: String, val name: String, val colorIndex: Int)

data class ConversationUiState(
    /** False when the app was built without google-services.json. */
    val firebaseConfigured: Boolean = false,
    val user: SignedInUser? = null,
    val signingIn: Boolean = false,
    val consentGiven: Boolean = false,
    val transcribing: Boolean = false,
    /** Fraction of the current chunk recorded so far (0..1). */
    val chunkProgress: Float = 0f,
    /** Chunks waiting for, or being processed by, Gemini. */
    val pendingChunks: Int = 0,
    val lines: List<TranscriptLine> = emptyList(),
    /** Every label used in [lines] (including merged ones) -> who it is now. */
    val speakers: Map<String, SpeakerInfo> = emptyMap(),
    val summary: ConversationSummary? = null,
    val summarizing: Boolean = false,
    val message: String? = null,
)

/**
 * App-wide owner of the transcription session:
 *
 *   ListenController capture (raw mic) -> resample to 16 kHz -> 30 s chunks
 *     -> Gemini (one chunk at a time, so speaker labels build on each other)
 *     -> SpeakerRegistry + 15-minute Transcript -> periodic summary.
 *
 * Nothing is written to disk except the one-time consent flag.
 */
class TranscriptionController private constructor(private val context: Context) {

    private val listen = ListenController.get(context)
    private val auth = AuthRepository(context)
    private val gemini by lazy { GeminiTranscriber() }
    private val prefs = context.getSharedPreferences("avifone", Context.MODE_PRIVATE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val registry = SpeakerRegistry()
    private val transcript = Transcript()
    private var transcriptVersion = 0
    private var summarizedVersion = 0

    /** Recorded chunks, processed strictly in order by one long-lived consumer. */
    private val chunks = Channel<Chunk>(Channel.UNLIMITED)
    private var pumpJob: Job? = null
    private var summaryJob: Job? = null

    private val _state = MutableStateFlow(
        ConversationUiState(
            firebaseConfigured = auth.isConfigured,
            consentGiven = prefs.getBoolean(KEY_CONSENT, false),
        )
    )
    val state: StateFlow<ConversationUiState> = _state

    /** An empty chunk is a marker: "recording stopped, write the final summary". */
    private class Chunk(val startMs: Long, val samples: FloatArray)

    init {
        scope.launch { auth.user.collect { user -> _state.update { it.copy(user = user) } } }
        // The notification's Stop turns capture off underneath us.
        scope.launch {
            listen.state.collect { s -> if (!s.capturing && _state.value.transcribing) stopPipeline() }
        }
        scope.launch { process() }
    }

    fun signIn(activity: Activity) {
        if (_state.value.signingIn) return
        _state.update { it.copy(signingIn = true, message = null) }
        scope.launch {
            val error = auth.signIn(activity)
            _state.update { it.copy(signingIn = false, message = error) }
        }
    }

    fun signOut() {
        stop()
        scope.launch { auth.signOut() }
    }

    fun giveConsent() {
        prefs.edit { putBoolean(KEY_CONSENT, true) }
        _state.update { it.copy(consentGiven = true) }
    }

    /** Must be called while the app is visible (it starts the microphone foreground service). */
    fun start() {
        val s = _state.value
        if (s.transcribing || s.user == null || !s.consentGiven) return
        if (!listen.setCaptureEnabled(true)) {
            _state.update { it.copy(message = listen.state.value.message ?: "Couldn't open the microphone") }
            return
        }
        // Drop audio captured before this session (e.g. a previous one's tail).
        val discard = FloatArray(READ_BLOCK)
        while (AudioEngine.readCapture(discard) > 0) Unit

        _state.update { it.copy(transcribing = true, chunkProgress = 0f, message = null) }
        pumpJob = scope.launch { pump() }
        summaryJob = scope.launch {
            while (isActive) {
                delay(SUMMARY_INTERVAL_MS)
                summarizeIfChanged()
            }
        }
    }

    fun stop() {
        if (!_state.value.transcribing) return
        listen.setCaptureEnabled(false)
        stopPipeline()
    }

    fun clearTranscript() {
        transcript.clear()
        transcriptVersion++
        _state.update { it.copy(summary = null) }
        publish()
    }

    fun renameSpeaker(label: String, name: String) {
        registry.rename(label, name)
        transcriptVersion++
        publish()
    }

    fun mergeSpeakers(from: String, into: String) {
        registry.merge(from, into)
        transcriptVersion++
        publish()
    }

    /** Stops recording; chunks already captured still get transcribed, then a final summary. */
    private fun stopPipeline() {
        val pump = pumpJob
        pumpJob = null
        pump?.cancel()  // pump() flushes its partial chunk in `finally`
        scope.launch {
            pump?.join()
            chunks.trySend(Chunk(System.currentTimeMillis(), FloatArray(0)))
        }
        summaryJob?.cancel()
        summaryJob = null
        _state.update { it.copy(transcribing = false, chunkProgress = 0f) }
    }

    private suspend fun pump() {
        val readBuffer = FloatArray(READ_BLOCK)
        var resampler: Resampler? = null
        val chunkSize = CHUNK_SECONDS * AudioClips.SAMPLE_RATE
        var chunk = FloatArray(chunkSize)
        var filled = 0
        var chunkStartMs = 0L

        fun emit() {
            if (filled > 0) chunks.trySend(Chunk(chunkStartMs, chunk.copyOf(filled)))
            chunk = FloatArray(chunkSize)
            filled = 0
        }

        try {
            while (true) {
                withContext(Dispatchers.Default) {
                    while (true) {
                        val count = AudioEngine.readCapture(readBuffer)
                        if (count <= 0) break
                        val rate = AudioEngine.captureSampleRate
                        if (rate <= 0) continue
                        if (resampler?.inputRate != rate) resampler = Resampler(rate, AudioClips.SAMPLE_RATE)
                        val samples = resampler!!.process(readBuffer, count)
                        var offset = 0
                        while (offset < samples.size) {
                            if (filled == 0) {
                                chunkStartMs = System.currentTimeMillis() -
                                    (samples.size - offset) * 1000L / AudioClips.SAMPLE_RATE
                            }
                            val n = minOf(samples.size - offset, chunkSize - filled)
                            samples.copyInto(chunk, filled, offset, offset + n)
                            filled += n
                            offset += n
                            if (filled == chunkSize) emit()
                        }
                    }
                }
                _state.update { it.copy(chunkProgress = filled.toFloat() / chunkSize) }
                delay(PUMP_INTERVAL_MS)
            }
        } finally {
            // Transcribe what was said right before Stop, unless it's just a blip.
            if (filled >= MIN_FLUSH_SECONDS * AudioClips.SAMPLE_RATE) emit()
        }
    }

    private suspend fun process() {
        for (chunk in chunks) {
            if (chunk.samples.isEmpty()) {
                summarizeIfChanged()  // recording stopped: final summary
                continue
            }
            val level = AudioClips.rmsDb(chunk.samples)
            Log.d(TAG, "chunk %.1f s, level %.1f dBFS".format(AudioClips.seconds(chunk.samples), level))
            if (level < SILENCE_DB) continue  // save quota on silence
            _state.update { it.copy(pendingChunks = it.pendingChunks + 1) }
            try {
                val request = withContext(Dispatchers.Default) {
                    ChunkRequest(chunk.samples, registry.referenceSpeakers(), registry.freeLabels())
                }
                val segments = transcribeWithRetry(request)
                Log.d(TAG, "chunk -> ${segments.size} segments, speakers ${segments.map { it.speaker }.distinct()}, " +
                    "${request.chunkOffset}s of reference audio")
                withContext(Dispatchers.Default) { registry.learn(chunk.samples, segments) }
                transcript.addChunk(chunk.startMs, segments)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Transcription failed", e)
                val endMs = chunk.startMs + chunk.samples.size * 1000L / AudioClips.SAMPLE_RATE
                transcript.addGap(chunk.startMs, endMs)
                _state.update { it.copy(message = describe(e)) }
            } finally {
                _state.update { it.copy(pendingChunks = it.pendingChunks - 1) }
            }
            transcriptVersion++
            publish()
        }
    }

    private suspend fun transcribeWithRetry(request: ChunkRequest): List<ChunkSegment> =
        try {
            gemini.transcribe(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            delay(if (e is QuotaExceededException) QUOTA_BACKOFF_MS else RETRY_DELAY_MS)
            gemini.transcribe(request)
        }

    private suspend fun summarizeIfChanged() {
        if (summarizedVersion == transcriptVersion || _state.value.summarizing) return
        val text = transcript.toPromptText(registry)
        if (text.isBlank()) return
        val version = transcriptVersion
        _state.update { it.copy(summarizing = true) }
        try {
            val summary = gemini.summarize(text, System.currentTimeMillis())
            summarizedVersion = version
            _state.update { it.copy(summary = summary) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Summary failed", e)
            _state.update { it.copy(message = "Summary: ${describe(e)}") }
        } finally {
            _state.update { it.copy(summarizing = false) }
        }
    }

    private fun publish() {
        if (transcript.prune(System.currentTimeMillis())) transcriptVersion++
        val lines = transcript.all
        val speakers = lines.filter { !it.isGap }.map { it.speaker }.distinct().associateWith { label ->
            registry.speaker(label).let { SpeakerInfo(it.label, it.name, it.colorIndex) }
        }
        _state.update { it.copy(lines = lines, speakers = speakers) }
    }

    private fun describe(e: Exception): String = when (e) {
        is QuotaExceededException -> "Gemini quota reached; some audio was skipped. Try again in a minute."
        else -> "Gemini request failed: ${e.message ?: e.javaClass.simpleName}"
    }

    companion object {
        private const val TAG = "Transcription"
        private const val KEY_CONSENT = "transcribe_consent"
        const val CHUNK_SECONDS = 30
        private const val MIN_FLUSH_SECONDS = 3
        private const val SILENCE_DB = -55f
        private const val READ_BLOCK = 4096
        private const val PUMP_INTERVAL_MS = 250L
        private const val SUMMARY_INTERVAL_MS = 60_000L
        private const val RETRY_DELAY_MS = 2_000L
        private const val QUOTA_BACKOFF_MS = 15_000L

        // Holds only the application context, so this can't leak an activity.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: TranscriptionController? = null

        fun get(context: Context): TranscriptionController = instance ?: synchronized(this) {
            instance ?: TranscriptionController(context.applicationContext).also { instance = it }
        }
    }
}
