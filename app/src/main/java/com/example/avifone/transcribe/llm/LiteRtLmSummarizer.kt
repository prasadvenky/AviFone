package com.example.avifone.transcribe.llm

import android.os.Build
import android.util.Log
import com.example.avifone.transcribe.ConversationSummary
import com.example.avifone.transcribe.Summarizer
import com.example.avifone.transcribe.SummaryJson
import com.example.avifone.transcribe.SummaryPrompt
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Summaries from a downloaded model through LiteRT-LM. Every call runs on [dispatcher],
 * a single thread, because the engine isn't thread-safe. The engine is loaded on first
 * use (several seconds) and kept until [close]; each summary gets a fresh conversation so
 * nothing accumulates in the context window.
 */
class LiteRtLmSummarizer(
    private val model: LlmModel,
    private val modelFile: File,
    private val dispatcher: CoroutineDispatcher,
    private val cacheDir: File,
) : Summarizer, AutoCloseable {

    override val label = "${model.displayName} · on device"

    private var engine: Engine? = null  // dispatcher thread only

    override suspend fun summarize(transcript: String, nowMs: Long): ConversationSummary =
        withContext(dispatcher) {
            val config = ConversationConfig(
                systemInstruction = Contents.of("${SummaryPrompt.INSTRUCTIONS}\n${SummaryPrompt.JSON_INSTRUCTION}"),
                samplerConfig = SamplerConfig(topK = 10, topP = 0.95, temperature = 0.3),
                maxOutputToken = MAX_OUTPUT_TOKENS,
                thinkingConfig = ThinkingConfig(enableThinking = false),
            )
            val reply = engine().createConversation(config).use { conversation ->
                conversation.sendMessage(SummaryPrompt.truncate(transcript))
            }
            val text = reply.text()
            Log.d(TAG, "Model answer: $text")
            SummaryJson.parse(text, nowMs)
        }

    /** GPU where the phone has it, otherwise CPU. The x86_64 emulator has no OpenCL. */
    private fun engine(): Engine {
        engine?.let { return it }
        cacheDir.mkdirs()
        val backends = if (Build.SUPPORTED_ABIS.firstOrNull() == "x86_64") {
            listOf(Backend.CPU())
        } else {
            listOf(Backend.GPU(), Backend.CPU())
        }
        var lastError: Exception? = null
        for (backend in backends) {
            val candidate = Engine(
                EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = backend,
                    cacheDir = cacheDir.absolutePath,
                )
            )
            try {
                candidate.initialize()
                Log.i(TAG, "${model.displayName} loaded on the ${backend.name} backend")
                engine = candidate
                return candidate
            } catch (e: Exception) {
                Log.w(TAG, "${backend.name} backend failed to initialize", e)
                runCatching { candidate.close() }
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("No LiteRT-LM backend available")
    }

    private fun Message.text(): String =
        contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    /** Must be called on [dispatcher]. */
    override fun close() {
        engine?.close()
        engine = null
    }

    companion object {
        private const val TAG = "LiteRtLm"
        private const val MAX_OUTPUT_TOKENS = 512
    }
}
