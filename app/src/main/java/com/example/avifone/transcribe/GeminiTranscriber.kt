package com.example.avifone.transcribe

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ConversationSummary(
    val topic: String,
    val points: List<String>,
    val actionItems: List<String>,
    val updatedAtMs: Long,
)

/** Calls Gemini through Firebase AI Logic (Gemini Developer API backend). */
class GeminiTranscriber {

    private val ai by lazy { Firebase.ai(backend = GenerativeBackend.googleAI()) }

    private val transcribeModel by lazy {
        ai.generativeModel(
            modelName = MODEL,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = SEGMENTS_SCHEMA
                temperature = 0f
                // Transcription needs no reasoning; thinking only adds latency, and each
                // 30 s chunk must come back in well under 30 s to keep up.
                thinkingConfig = thinkingConfig { thinkingLevel = ThinkingLevel.MINIMAL }
            },
        )
    }

    private val summaryModel by lazy {
        ai.generativeModel(
            modelName = MODEL,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = SUMMARY_SCHEMA
                temperature = 0.3f
                thinkingConfig = thinkingConfig { thinkingLevel = ThinkingLevel.LOW }
            },
        )
    }

    suspend fun transcribe(request: ChunkRequest): List<ChunkSegment> {
        val prompt = withContext(Dispatchers.Default) {
            content {
                inlineData(request.wav(), "audio/wav")
                text(request.prompt())
            }
        }
        val text = transcribeModel.generateContent(prompt).text ?: return emptyList()
        return request.parse(text)
    }

    suspend fun summarize(transcript: String, nowMs: Long): ConversationSummary {
        val prompt = """
            Below is the transcript of the last (up to) 15 minutes of a conversation, one line
            per utterance as "[time] speaker: text". Summarize it for someone who just joined.
            - topic: one short line saying what the conversation is about.
            - points: up to 6 concise key points, naming who said what where it matters.
            - actionItems: decisions, open questions or to-dos; empty if there are none.
            Write in the language of the conversation.

            $transcript
        """.trimIndent()
        val json = JSONObject(summaryModel.generateContent(prompt).text?.trim().orEmpty().ifEmpty { "{}" })
        return ConversationSummary(
            topic = json.optString("topic"),
            points = json.optJSONArray("points").strings(),
            actionItems = json.optJSONArray("actionItems").strings(),
            updatedAtMs = nowMs,
        )
    }

    private fun org.json.JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }.filter { it.isNotBlank() }

    companion object {
        /** Stable Flash model available through Firebase AI Logic. */
        const val MODEL = "gemini-3.5-flash"

        private val SEGMENTS_SCHEMA = Schema.obj(
            mapOf(
                "segments" to Schema.array(
                    Schema.obj(
                        mapOf(
                            "speaker" to Schema.enumeration(SpeakerRegistry.LABELS),
                            "start" to Schema.double("Start time in seconds from the beginning of the audio"),
                            "end" to Schema.double("End time in seconds from the beginning of the audio"),
                            "text" to Schema.string("Exactly what was said"),
                        )
                    )
                )
            )
        )

        private val SUMMARY_SCHEMA = Schema.obj(
            mapOf(
                "topic" to Schema.string(),
                "points" to Schema.array(Schema.string()),
                "actionItems" to Schema.array(Schema.string()),
            )
        )
    }
}
