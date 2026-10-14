package com.example.avifone.transcribe

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Lenient reader for a model's summary answer. Gemini returns clean JSON thanks to its
 * response schema; the small on-device models may wrap it in code fences, think tags or
 * prose, or ignore the format entirely. This never throws: at worst the text's lines
 * become the key points.
 */
object SummaryJson {

    fun parse(raw: String, nowMs: Long): ConversationSummary {
        val text = raw
            .replace(THINK_BLOCK, "")
            .replace(THINK_TAG, "")
            .replace(FENCE, "")
            .trim()
        extractObject(text)?.let { obj ->
            return ConversationSummary(
                topic = obj.optString("topic").trim(),
                points = obj.strings("points", "keyPoints", "key_points"),
                actionItems = obj.strings("actionItems", "action_items", "actions"),
                updatedAtMs = nowMs,
            )
        }
        val points = text.lines()
            .map { it.trim().trimStart('-', '•', '*', '☐').replace(NUMBERING, "").trim() }
            .filter { it.isNotEmpty() }
        return ConversationSummary(topic = "", points = points, actionItems = emptyList(), updatedAtMs = nowMs)
    }

    private fun extractObject(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JSONObject(text.substring(start, end + 1))
        } catch (_: JSONException) {
            null
        }
    }

    /** The first of [keys] that holds an array; items may be strings or objects with a text field. */
    private fun JSONObject.strings(vararg keys: String): List<String> {
        val array: JSONArray = keys.firstNotNullOfOrNull { optJSONArray(it) } ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            when (val item = array.opt(i)) {
                is String -> item
                is JSONObject -> item.optString("text").ifEmpty { item.optString("point") }
                null -> null
                else -> item.toString()
            }
        }.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private val THINK_BLOCK = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
    private val THINK_TAG = Regex("</?think>")
    private val FENCE = Regex("```[A-Za-z]*")
    private val NUMBERING = Regex("^\\d+[.)]\\s*")
}
