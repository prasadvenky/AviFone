package com.example.avifone.transcribe.llm

import android.util.Log
import com.example.avifone.transcribe.ConversationSummary
import com.example.avifone.transcribe.Summarizer
import com.example.avifone.transcribe.SummaryJson
import com.example.avifone.transcribe.SummaryPrompt
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Summaries from Gemini Nano through the ML Kit Prompt API. Android's AICore service owns
 * the model, so there is nothing for us to download or store; it only exists on recent
 * flagship phones.
 */
class NanoSummarizer : Summarizer {

    override val label = "Gemini Nano · on device"

    private val model by lazy { Generation.getClient() }

    override suspend fun summarize(transcript: String, nowMs: Long): ConversationSummary {
        val request = generateContentRequest(TextPart(SummaryPrompt.local(transcript))) {
            temperature = 0.3f
            maxOutputTokens = 512
        }
        val response = model.generateContent(request)
        val text = response.candidates.firstOrNull()?.text.orEmpty()
        Log.d(TAG, "Model answer: $text")
        return SummaryJson.parse(text, nowMs)
    }

    companion object {
        private const val TAG = "GeminiNano"

        /**
         * A [FeatureStatus] constant, or null when AICore can't be reached at all (the
         * emulator, phones without it). Never throws.
         */
        suspend fun status(): Int? = try {
            withTimeoutOrNull(10_000) { Generation.getClient().checkStatus() }
        } catch (e: Exception) {
            Log.i(TAG, "Gemini Nano not reachable: ${e.message}")
            null
        }

        fun download(): Flow<DownloadStatus> = Generation.getClient().download()
    }
}
