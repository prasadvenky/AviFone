package com.example.avifone.transcribe

/** The summary instructions shared by every [Summarizer], cloud or on-device. */
object SummaryPrompt {

    /** Character budget for transcript text sent to the small on-device models. */
    const val LOCAL_MAX_CHARS = 6000

    const val OMITTED_MARKER = "(earlier part omitted)"

    val INSTRUCTIONS = """
        Below is the transcript of the last (up to) 15 minutes of a conversation, one line
        per utterance as "[time] speaker: text". It was produced by on-device speech
        recognition, so expect some misheard words; infer the intended meaning.
        Summarize it for someone who just joined.
        - topic: one short line saying what the conversation is about.
        - points: up to 6 concise key points, naming who said what where it matters.
        - actionItems: decisions, open questions or to-dos; empty if there are none.
        Write in the language of the conversation.
    """.trimIndent()

    /** Small models have no structured-output mode, so the format is spelled out. */
    const val JSON_INSTRUCTION =
        "Respond with only a JSON object of the form " +
            "{\"topic\": string, \"points\": [string], \"actionItems\": [string]} " +
            "and nothing else: no markdown, no explanation."

    /** Today's Gemini prompt; the response schema takes care of the format. */
    fun cloud(transcript: String): String = "$INSTRUCTIONS\n\n$transcript"

    /** Prompt for the on-device models: explicit JSON format and a bounded transcript. */
    fun local(transcript: String): String = "$INSTRUCTIONS\n$JSON_INSTRUCTION\n\n${truncate(transcript)}"

    /**
     * Keeps the most recent whole lines that fit in [maxChars]. When anything was dropped
     * the result starts with [OMITTED_MARKER]. A single line longer than the budget is
     * cut to its tail.
     */
    fun truncate(transcript: String, maxChars: Int = LOCAL_MAX_CHARS): String {
        if (transcript.length <= maxChars) return transcript
        val budget = (maxChars - OMITTED_MARKER.length - 1).coerceAtLeast(0)
        val lines = transcript.lines()
        val kept = ArrayDeque<String>()
        var used = 0
        for (line in lines.asReversed()) {
            val cost = line.length + if (kept.isEmpty()) 0 else 1
            if (used + cost > budget) break
            kept.addFirst(line)
            used += cost
        }
        if (kept.isEmpty()) kept.addFirst(lines.last().takeLast(budget))
        return OMITTED_MARKER + "\n" + kept.joinToString("\n")
    }
}
