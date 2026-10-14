package com.example.avifone.transcribe

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import org.json.JSONObject

data class ConversationSummary(
    val topic: String,
    val points: List<String>,
    val actionItems: List<String>,
    val updatedAtMs: Long,
)

/**
 * The only cloud call in the conversation feature: summarizes transcript *text* with
 * Gemini through Firebase AI Logic. Audio never leaves the device.
 */
class GeminiSummarizer {

    private val model by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = MODEL,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = SUMMARY_SCHEMA
                temperature = 0.3f
                thinkingConfig = thinkingConfig { thinkingLevel = ThinkingLevel.LOW }
            },
        )
    }

    suspend fun summarize(transcript: String, nowMs: Long): ConversationSummary {
        val prompt = """
            Below is the transcript of the last (up to) 15 minutes of a conversation, one line
            per utterance as "[time] speaker: text". It was produced by on-device speech
            recognition, so expect some misheard words; infer the intended meaning.
            Summarize it for someone who just joined.
            - topic: one short line saying what the conversation is about.
            - points: up to 6 concise key points, naming who said what where it matters.
            - actionItems: decisions, open questions or to-dos; empty if there are none.
            Write in the language of the conversation.

            $transcript
        """.trimIndent()
        val json = JSONObject(model.generateContent(prompt).text?.trim().orEmpty().ifEmpty { "{}" })
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

        private val SUMMARY_SCHEMA = Schema.obj(
            mapOf(
                "topic" to Schema.string(),
                "points" to Schema.array(Schema.string()),
                "actionItems" to Schema.array(Schema.string()),
            )
        )
    }
}
