package com.example.avifone.transcribe.local

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs the real on-device models (bundled app assets) on a known 4-speaker recording,
 * fed straight into LocalTranscriber so the device microphone isn't involved.
 * The recording uses synthetic voices; see assets/conversation4_truth.tsv.
 */
@RunWith(AndroidJUnit4::class)
class LocalTranscriberTest {

    private data class Turn(val start: Double, val end: Double, val voice: String, val text: String)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun readWav16k(name: String): FloatArray {
        val bytes = instrumentation.context.assets.open(name).use { it.readBytes() }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16_000, buf.getInt(24))
        // Find the "data" chunk (ffmpeg may add a LIST chunk before it).
        var pos = 12
        while (String(bytes, pos, 4) != "data") pos += 8 + buf.getInt(pos + 4)
        val size = buf.getInt(pos + 4)
        return FloatArray(size / 2) { buf.getShort(pos + 8 + it * 2) / 32768f }
    }

    private fun readTruth(): List<Turn> =
        instrumentation.context.assets.open("conversation4_truth.tsv").bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t').let { f -> Turn(f[0].toDouble(), f[1].toDouble(), f[2], f[3]) } }

    private fun words(s: String) = s.lowercase().replace(Regex("[^a-z0-9' ]"), " ").split(' ').filter { it.isNotBlank() }

    @Test
    fun transcribesAndSeparatesFourSpeakers() {
        val audio = readWav16k("conversation4_16k.wav")
        val turns = readTruth()
        val started = System.nanoTime()
        val utterances = mutableListOf<LocalUtterance>()
        LocalTranscriber(instrumentation.targetContext.assets).use { t ->
            var i = 0
            while (i < audio.size) {  // feed in 100 ms blocks, like the live pump
                val block = audio.copyOfRange(i, minOf(i + 1600, audio.size))
                utterances += t.accept(block)
                i += block.size
            }
            utterances += t.flush()
            val seconds = (System.nanoTime() - started) / 1e9
            Log.i(TAG, "processed %.1f s of audio in %.1f s; %d utterances, %d speakers"
                .format(audio.size / 16000.0, seconds, utterances.size, t.clusterer.speakerCount))
            assertEquals("speakers found", 4, t.clusterer.speakerCount)
        }

        // Attribute each utterance to the turn it overlaps most.
        fun truthOf(u: LocalUtterance): Turn {
            val a = u.startSample / 16000.0
            val b = u.endSample / 16000.0
            return turns.maxBy { minOf(b, it.end) - maxOf(a, it.start) }
        }
        utterances.forEach { Log.i(TAG, "${it.speaker} [${truthOf(it).voice}]: ${it.text}") }

        // Speaker consistency: each true voice should map to one label (purity).
        val byVoice = utterances.groupBy { truthOf(it).voice }
        val correct = byVoice.values.sumOf { group -> group.groupingBy { it.speaker }.eachCount().values.max() }
        val purity = correct.toDouble() / utterances.size
        val labelsPerVoice = byVoice.mapValues { (_, g) -> g.groupingBy { it.speaker }.eachCount().maxBy { it.value }.key }
        Log.i(TAG, "purity %.2f, voice->label %s".format(purity, labelsPerVoice))
        assertTrue("purity $purity", purity >= 0.9)
        assertEquals("each voice has its own label", 4, labelsPerVoice.values.toSet().size)

        // Text: most reference words of most turns should be recognized.
        val recognized = turns.count { turn ->
            val hyp = utterances.filter { truthOf(it) == turn }.flatMap { words(it.text) }.toSet()
            val ref = words(turn.text)
            ref.count { it in hyp }.toDouble() / ref.size >= 0.6
        }
        Log.i(TAG, "turns recognized: $recognized / ${turns.size}")
        assertTrue("recognized $recognized of ${turns.size} turns", recognized >= 14)
    }

    companion object {
        private const val TAG = "LocalTranscriberTest"
    }
}
