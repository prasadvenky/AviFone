package com.example.avifone.transcribe

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig

/**
 * The only cloud call in the conversation feature: summarizes transcript *text* with
 * Gemini through Firebase AI Logic. Audio never leaves the device.
 */
class GeminiSummarizer : Summarizer {

    override val label = "Gemini (cloud)"

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

    override suspend fun summarize(transcript: String, nowMs: Long): ConversationSummary {
        val text = model.generateContent(SummaryPrompt.cloud(transcript)).text.orEmpty()
        return SummaryJson.parse(text, nowMs)
    }

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
