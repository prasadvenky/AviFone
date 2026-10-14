package com.example.avifone.transcribe.llm

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Runs the real Local AI summary model on a fixed transcript. The model file isn't in the
 * APK: download it once through the app (Conversation tab -> Local AI -> Download), and
 * this test, which shares the app's storage, picks it up. It is skipped when absent.
 */
@RunWith(AndroidJUnit4::class)
class LiteRtLmSummarizerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun summarizesAConversation() = runBlocking {
        val model = ModelCatalog.DEFAULT
        val file = File(context.filesDir, "models/llm/${model.fileName}")
        assumeTrue("Model not downloaded yet: ${file.path}", file.exists())

        val summarizer = LiteRtLmSummarizer(model, file, Dispatchers.Default, File(context.cacheDir, "litertlm-test"))
        try {
            val started = System.currentTimeMillis()
            val summary = summarizer.summarize(TRANSCRIPT, nowMs = started)
            Log.i("LiteRtLmTest", "Summary after ${System.currentTimeMillis() - started} ms: $summary")
            assertTrue("no points in $summary", summary.points.isNotEmpty())
            val text = (listOf(summary.topic) + summary.points + summary.actionItems).joinToString(" ").lowercase()
            assertTrue("summary doesn't mention the trip: $text", listOf("trip", "portland", "train", "friday").any { it in text })
        } finally {
            withContext(EmptyCoroutineContext) { summarizer.close() }
        }
    }

    companion object {
        private val TRANSCRIPT = """
            [10:02] Ana: So are we still doing the Portland trip next weekend?
            [10:02] Ben: I think so. I checked the train, there's one at eight on Friday morning.
            [10:03] Ana: Eight is fine. Did you book the hotel yet?
            [10:03] Ben: Not yet, I wanted to confirm with Chloe first, she wasn't sure she could come.
            [10:04] Chloe: I can come, but I have to be back by Sunday evening for work.
            [10:04] Ana: Okay, so two nights. Ben, can you book the hotel tonight?
            [10:05] Ben: Yes, I'll do it tonight and send you both the confirmation.
            [10:05] Chloe: And I'll buy the train tickets, send me your full names.
        """.trimIndent()
    }
}
