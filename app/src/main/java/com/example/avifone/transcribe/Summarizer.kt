package com.example.avifone.transcribe

data class ConversationSummary(
    val topic: String,
    val points: List<String>,
    val actionItems: List<String>,
    val updatedAtMs: Long,
)

/** Turns the last 15 minutes of transcript text into a [ConversationSummary]. */
interface Summarizer {
    /** Short name shown next to the summary, e.g. "Gemini (cloud)". */
    val label: String

    suspend fun summarize(transcript: String, nowMs: Long): ConversationSummary
}

/** Where the summary is written: by Gemini in the cloud, or by a model on this phone. */
enum class SummaryMode {
    CLOUD, LOCAL;

    companion object {
        fun fromPref(value: String?): SummaryMode? = entries.firstOrNull { it.name == value }
    }
}

/** Which on-device engine the LOCAL mode uses on this phone. */
enum class LocalEngine {
    /** Not probed yet. */
    NONE,
    /** Gemini Nano through ML Kit (AICore manages the model). */
    NANO,
    /** LiteRT-LM running a model file we download into app storage. */
    LITERT,
}

/** Lifecycle of a downloadable on-device model (the summary LLM and the speech models). */
sealed interface LocalModelState {
    data object Probing : LocalModelState
    data class NotDownloaded(val resumeBytes: Long, val totalBytes: Long?) : LocalModelState
    data class Downloading(val bytes: Long, val totalBytes: Long?) : LocalModelState
    data object Verifying : LocalModelState
    /** Unpacking an archive after the download was verified. */
    data object Installing : LocalModelState
    data object Ready : LocalModelState
    data class Failed(val reason: String, val resumeBytes: Long) : LocalModelState
}
