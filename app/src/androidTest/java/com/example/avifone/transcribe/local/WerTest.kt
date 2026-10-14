package com.example.avifone.transcribe.local

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.avifone.transcribe.speech.SpeechLanguage
import com.example.avifone.transcribe.speech.SpeechModel
import com.example.avifone.transcribe.speech.SpeechModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Word error rate of the real Android pipeline on the labelled clips in assets/wer/manifest.tsv
 * (file, start_s, end_s, text; see tools/README.md for adding Svarah Indian-English clips).
 *
 * Runs once per catalogue model that is on the device: the built-in one always, and any model
 * downloaded through Settings (files/models/speech/<id>/, kept by the leave-installed flag).
 * Results go to logcat under the WerTest tag; the assertions are loose smoke bounds.
 */
@RunWith(AndroidJUnit4::class)
class WerTest {

    private data class Clip(val file: String, val start: Double?, val end: Double?, val text: String)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val assets get() = instrumentation.context.assets
    private val app get() = instrumentation.targetContext

    private fun readWav16k(name: String): FloatArray {
        val bytes = assets.open(name).use { it.readBytes() }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("$name must be 16 kHz", 16_000, buf.getInt(24))
        var pos = 12
        while (String(bytes, pos, 4) != "data") pos += 8 + buf.getInt(pos + 4)
        val size = buf.getInt(pos + 4)
        return FloatArray(size / 2) { buf.getShort(pos + 8 + it * 2) / 32768f }
    }

    private fun readManifest(): List<Clip> =
        assets.open("wer/manifest.tsv").bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val f = line.split('\t')
                if (f.size == 2) Clip(f[0], null, null, f[1])
                else Clip(f[0], f[1].toDoubleOrNull(), f[2].toDoubleOrNull(), f.drop(3).joinToString("\t"))
            }

    private fun words(s: String) =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}' ]"), " ").split(' ').filter { it.isNotBlank() }

    /** Levenshtein distance on word lists: (substitutions + deletions + insertions). */
    private fun editDistance(ref: List<String>, hyp: List<String>): Int {
        var prev = IntArray(hyp.size + 1) { it }
        for (i in 1..ref.size) {
            val cur = IntArray(hyp.size + 1)
            cur[0] = i
            for (j in 1..hyp.size) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (ref[i - 1] == hyp[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[hyp.size]
    }

    /** Models whose files exist on this device, in catalogue order. */
    private fun installedModels(): List<SpeechModel> = SpeechModelCatalog.ALL.filter { model ->
        model.builtIn || model.installedFiles.all { File(File(app.filesDir, "models/speech/${model.id}"), it.name).length() == it.sizeBytes }
    }

    @Test
    fun wordErrorRatePerInstalledModel() {
        val clips = readManifest()
        val audio = clips.map { it.file }.distinct().associateWith(::readWav16k)
        val results = mutableMapOf<String, Double>()
        for (model in installedModels()) {
            val dir = File(app.filesDir, "models/speech/${model.id}")
            val recognizer = TextRecognizer.create(model, app.assets, dir, SpeechLanguage.ENGLISH)
            var errors = 0
            var refWords = 0
            val started = System.nanoTime()
            var seconds = 0.0
            LocalTranscriber(app.assets, recognizer).use { t ->
                for (clip in clips) {
                    val full = audio.getValue(clip.file)
                    val a = ((clip.start ?: 0.0) * 16000).toInt()
                    val b = clip.end?.let { (it * 16000).toInt() } ?: full.size
                    val samples = full.copyOfRange(a, b)
                    seconds += samples.size / 16000.0
                    t.startSession()
                    val got = mutableListOf<LocalUtterance>()
                    got += t.accept(FloatArray(16000))  // the pause before someone speaks, as live
                    var i = 0
                    while (i < samples.size) {
                        val block = samples.copyOfRange(i, minOf(i + 1600, samples.size))
                        got += t.accept(block)
                        i += block.size
                    }
                    got += t.flush()
                    val hyp = words(got.joinToString(" ") { it.text })
                    val ref = words(clip.text)
                    val e = editDistance(ref, hyp)
                    errors += e
                    refWords += ref.size
                    Log.i(TAG, "${model.id} [${e}/${ref.size}] REF: ${clip.text}\n    HYP: ${got.joinToString(" ") { it.text }}")
                }
            }
            val wer = errors.toDouble() / refWords
            val elapsed = (System.nanoTime() - started) / 1e9
            Log.i(TAG, "%s: WER %.1f%% (%d errors / %d words), %.0f s of audio in %.1f s (RTF %.2f)"
                .format(model.id, 100 * wer, errors, refWords, seconds, elapsed, elapsed / seconds))
            results[model.id] = wer
        }
        Log.i(TAG, "summary: " + results.entries.joinToString { "%s %.1f%%".format(it.key, 100 * it.value) })
        assertTrue("built-in model must have run", SpeechModelCatalog.MOONSHINE_TINY.id in results)
        results.forEach { (id, wer) ->
            val bound = if (id == SpeechModelCatalog.MOONSHINE_TINY.id) 0.30 else 0.25
            assertTrue("$id WER $wer above $bound", wer <= bound)
        }
    }

    companion object {
        private const val TAG = "WerTest"
    }
}
