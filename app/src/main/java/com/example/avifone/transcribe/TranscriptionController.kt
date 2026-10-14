package com.example.avifone.transcribe

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.example.avifone.AudioEngine
import com.example.avifone.ListenController
import com.example.avifone.transcribe.local.LocalTranscriber
import com.example.avifone.transcribe.local.LocalUtterance
import com.google.firebase.ai.type.QuotaExceededException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

data class SpeakerInfo(val label: String, val name: String, val colorIndex: Int)

data class ConversationUiState(
    /** False when the app was built without google-services.json (summary unavailable). */
    val firebaseConfigured: Boolean = false,
    val user: SignedInUser? = null,
    val signingIn: Boolean = false,
    val consentGiven: Boolean = false,
    val transcribing: Boolean = false,
    /** The on-device models are being loaded (first start only). */
    val loadingModels: Boolean = false,
    /** Someone is talking right now (voice activity detector). */
    val speechActive: Boolean = false,
    val lines: List<TranscriptLine> = emptyList(),
    /** Every label used in [lines] (including merged ones) -> who it is now. */
    val speakers: Map<String, SpeakerInfo> = emptyMap(),
    val summary: ConversationSummary? = null,
    val summarizing: Boolean = false,
    val message: String? = null,
)

/**
 * App-wide owner of the conversation session:
 *
 *   ListenController capture (raw mic) -> resample to 16 kHz -> LocalTranscriber
 *     (on-device VAD + speech-to-text + speaker labels) -> 15-minute Transcript
 *     -> Gemini summary of the text (only when signed in).
 *
 * Audio never leaves the device. Nothing is written to disk except the consent flag.
 */
class TranscriptionController private constructor(private val context: Context) {

    private val listen = ListenController.get(context)
    private val auth = AuthRepository(context)
    private val summarizer by lazy { GeminiSummarizer() }
    private val prefs = context.getSharedPreferences("avifone", Context.MODE_PRIVATE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** The native speech stack isn't thread-safe; all of it runs on this one thread. */
    private val speechThread = Executors.newSingleThreadExecutor { Thread(it, "local-speech") }
        .asCoroutineDispatcher()
    private var transcriber: LocalTranscriber? = null  // speech thread only; kept across sessions

    private val registry = SpeakerRegistry()
    private val transcript = Transcript()
    private var transcriptVersion = 0
    private var summarizedVersion = 0

    private var pumpJob: Job? = null
    private var summaryJob: Job? = null

    private val _state = MutableStateFlow(
        ConversationUiState(
            firebaseConfigured = auth.isConfigured,
            consentGiven = prefs.getBoolean(KEY_CONSENT, false),
        )
    )
    val state: StateFlow<ConversationUiState> = _state

    init {
        scope.launch { auth.user.collect { user -> _state.update { it.copy(user = user) } } }
        // The notification's Stop turns capture off underneath us.
        scope.launch {
            listen.state.collect { s -> if (!s.capturing && _state.value.transcribing) stopPipeline() }
        }
    }

    fun signIn(activity: Activity) {
        if (_state.value.signingIn) return
        _state.update { it.copy(signingIn = true, message = null) }
        scope.launch {
            val error = auth.signIn(activity)
            _state.update { it.copy(signingIn = false, message = error) }
            if (error == null) summarizeIfChanged()
        }
    }

    fun signOut() {
        scope.launch { auth.signOut() }
        _state.update { it.copy(summary = null) }
    }

    fun giveConsent() {
        prefs.edit { putBoolean(KEY_CONSENT, true) }
        _state.update { it.copy(consentGiven = true) }
    }

    /** Must be called while the app is visible (it starts the microphone foreground service). */
    fun start() {
        val s = _state.value
        if (s.transcribing || !s.consentGiven) return
        if (!listen.setCaptureEnabled(true)) {
            _state.update { it.copy(message = listen.state.value.message ?: "Couldn't open the microphone") }
            return
        }
        // Drop audio captured before this session (e.g. a previous one's tail).
        val discard = FloatArray(READ_BLOCK)
        while (AudioEngine.readCapture(discard) > 0) Unit

        _state.update { it.copy(transcribing = true, loadingModels = transcriber == null, message = null) }
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
        val (source, target) = registry.merge(from, into) ?: return
        scope.launch(speechThread) { transcriber?.clusterer?.merge(source, target) }
        transcriptVersion++
        publish()
    }

    /** Stops recording; the utterance in progress is still transcribed, then a final summary. */
    private fun stopPipeline() {
        val pump = pumpJob
        pumpJob = null
        pump?.cancel()  // pump() flushes the last utterance in `finally`
        summaryJob?.cancel()
        summaryJob = null
        _state.update { it.copy(transcribing = false, speechActive = false) }
        scope.launch {
            pump?.join()
            summarizeIfChanged()
        }
    }

    private suspend fun pump() {
        val engine = try {
            withContext(speechThread) { transcriber ?: LocalTranscriber(context.assets).also { transcriber = it } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't load speech models", e)
            _state.update { it.copy(loadingModels = false, message = "Couldn't load the speech models: ${e.message}") }
            stop()
            return
        }
        _state.update { it.copy(loadingModels = false) }

        // The transcriber is reused across sessions, so its sample positions keep counting;
        // anchor this session's wall clock to the count at the start.
        val sessionStartMs = System.currentTimeMillis()
        val sessionStartSample = withContext(speechThread) { engine.samplesAccepted }
        fun add(utterances: List<LocalUtterance>) {
            if (utterances.isEmpty()) return
            for (u in utterances) {
                transcript.add(
                    speaker = u.speaker,
                    text = u.text,
                    startMs = sessionStartMs + (u.startSample - sessionStartSample) * 1000 / LocalTranscriber.SAMPLE_RATE,
                    endMs = sessionStartMs + (u.endSample - sessionStartSample) * 1000 / LocalTranscriber.SAMPLE_RATE,
                )
            }
            transcriptVersion++
            publish()
        }

        val readBuffer = FloatArray(READ_BLOCK)
        var resampler: Resampler? = null
        try {
            while (true) {
                val done = withContext(speechThread) {
                    val out = mutableListOf<LocalUtterance>()
                    while (true) {
                        val count = AudioEngine.readCapture(readBuffer)
                        if (count <= 0) break
                        val rate = AudioEngine.captureSampleRate
                        if (rate <= 0) continue
                        if (resampler?.inputRate != rate) resampler = Resampler(rate, LocalTranscriber.SAMPLE_RATE)
                        out += engine.accept(resampler!!.process(readBuffer, count))
                    }
                    out
                }
                add(done)
                _state.update { it.copy(speechActive = engine.isSpeechDetected) }
                delay(PUMP_INTERVAL_MS)
            }
        } finally {
            withContext(NonCancellable) { add(withContext(speechThread) { engine.flush() }) }
        }
    }

    private suspend fun summarizeIfChanged() {
        val s = _state.value
        if (!s.firebaseConfigured || s.user == null) return
        if (summarizedVersion == transcriptVersion || s.summarizing) return
        val text = transcript.toPromptText(registry)
        if (text.isBlank()) return
        val version = transcriptVersion
        _state.update { it.copy(summarizing = true) }
        try {
            val summary = summarizer.summarize(text, System.currentTimeMillis())
            summarizedVersion = version
            _state.update { it.copy(summary = summary) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Summary failed", e)
            val reason = if (e is QuotaExceededException) "Gemini quota reached; will retry" else e.message
            _state.update { it.copy(message = "Summary: $reason") }
        } finally {
            _state.update { it.copy(summarizing = false) }
        }
    }

    private fun publish() {
        if (transcript.prune(System.currentTimeMillis())) transcriptVersion++
        val lines = transcript.all
        val speakers = lines.map { it.speaker }.distinct().associateWith { label ->
            registry.speaker(label).let { SpeakerInfo(it.label, it.name, it.colorIndex) }
        }
        _state.update { it.copy(lines = lines, speakers = speakers) }
    }

    companion object {
        private const val TAG = "Transcription"
        // New key: the local version's consent text differs from the Gemini-audio one.
        private const val KEY_CONSENT = "transcribe_consent_local"
        private const val READ_BLOCK = 4096
        private const val PUMP_INTERVAL_MS = 200L
        private const val SUMMARY_INTERVAL_MS = 60_000L

        // Holds only the application context, so this can't leak an activity.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: TranscriptionController? = null

        fun get(context: Context): TranscriptionController = instance ?: synchronized(this) {
            instance ?: TranscriptionController(context.applicationContext).also { instance = it }
        }
    }
}
