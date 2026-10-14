package com.example.avifone.transcribe.speech

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.example.avifone.transcribe.LocalModelState
import com.example.avifone.transcribe.llm.ResumableDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Downloads, verifies, unpacks and deletes the speech models in [SpeechModelCatalog], under
 * `files/models/speech/<id>/`. Public methods are called from the main thread; [states] may be
 * updated from workers. Downloads run in [scope] (the controller's), so they survive rotation,
 * and a killed process resumes from the `.part` files on the next tap.
 */
class SpeechModelManager(private val context: Context, private val scope: CoroutineScope) {

    private val root = File(context.filesDir, "models/speech")
    private val downloader = ResumableDownloader()
    private val jobs = mutableMapOf<String, Job>()

    private val _states = MutableStateFlow(SpeechModelCatalog.ALL.associate { it.id to stateFromDisk(it) })
    /** Model id -> lifecycle state, for every catalogue entry. */
    val states: StateFlow<Map<String, LocalModelState>> = _states

    /** Where a downloadable model's files live. */
    fun dir(model: SpeechModel): File = File(root, model.id)

    /** True for the built-in model and for downloads that completed. */
    fun isInstalled(model: SpeechModel): Boolean =
        model.builtIn || model.installedFiles.all { File(dir(model), it.name).length() == it.sizeBytes }

    /** This phone's RAM in MB, to warn before loading a model it can't hold. */
    val totalRamMb: Int by lazy {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        (info.totalMem / (1024 * 1024)).toInt()
    }

    fun download(id: String) {
        val model = SpeechModelCatalog.byId(id) ?: return
        val source = model.source ?: return
        if (jobs[id]?.isActive == true) return
        val current = _states.value[id]
        if (current !is LocalModelState.NotDownloaded && current !is LocalModelState.Failed) return
        jobs[id] = scope.launch {
            try {
                when (source) {
                    is ModelSource.Files -> downloadFiles(model, source)
                    is ModelSource.Archive -> downloadArchive(model, source)
                }
                set(id, LocalModelState.Ready)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Download of ${model.id} failed", e)
                set(id, LocalModelState.Failed(e.message ?: e.javaClass.simpleName, resumeBytes(model)))
            }
        }
    }

    /** Stops a download; the part files stay so it can resume later. */
    fun cancel(id: String) {
        val job = jobs.remove(id) ?: return
        job.cancel()
        scope.launch {
            job.join()
            SpeechModelCatalog.byId(id)?.let { set(id, stateFromDisk(it)) }
        }
    }

    /** Removes a downloaded model (and any partial download) from this phone. */
    fun delete(id: String) {
        val model = SpeechModelCatalog.byId(id) ?: return
        if (model.builtIn) return
        val job = jobs.remove(id)
        job?.cancel()
        scope.launch {
            job?.join()
            withContext(Dispatchers.IO) { dir(model).deleteRecursively() }
            set(id, stateFromDisk(model))
        }
    }

    private suspend fun downloadFiles(model: SpeechModel, source: ModelSource.Files) {
        val dir = dir(model)
        val total = model.downloadBytes
        var doneBefore = 0L
        for (file in source.files) {
            val dest = File(dir, file.name)
            val part = File(dir, file.name + ".part")
            if (dest.length() == file.sizeBytes) {
                doneBefore += file.sizeBytes
                continue
            }
            set(model.id, LocalModelState.Downloading(doneBefore + part.length(), total))
            downloader.download(file.url, dest, part, file.sizeBytes) { bytes, _ ->
                set(model.id, LocalModelState.Downloading(doneBefore + bytes, total))
            }
            file.sha256?.let { expected ->
                set(model.id, LocalModelState.Verifying)
                val actual = withContext(Dispatchers.IO) { ResumableDownloader.sha256(dest) }
                if (actual != expected) {
                    dest.delete()
                    throw IOException("${file.name} didn't match its checksum; try again")
                }
            }
            doneBefore += file.sizeBytes
        }
    }

    private suspend fun downloadArchive(model: SpeechModel, source: ModelSource.Archive) {
        val dir = dir(model)
        val archive = File(dir, ARCHIVE_NAME)
        val part = File(dir, "$ARCHIVE_NAME.part")
        if (archive.length() != source.sizeBytes) {
            set(model.id, LocalModelState.Downloading(part.length(), source.sizeBytes))
            downloader.download(source.url, archive, part, source.sizeBytes) { bytes, _ ->
                set(model.id, LocalModelState.Downloading(bytes, source.sizeBytes))
            }
        }
        set(model.id, LocalModelState.Verifying)
        val actual = withContext(Dispatchers.IO) { ResumableDownloader.sha256(archive) }
        if (actual != source.sha256) {
            archive.delete()
            throw IOException("The download didn't match its checksum; try again")
        }
        set(model.id, LocalModelState.Installing)
        withContext(Dispatchers.IO) { extract(archive, dir, source.members) }
        if (!isInstalled(model)) throw IOException("The archive didn't contain all the model files")
        archive.delete()
    }

    /** Writes just the wanted members of a tar.bz2 into [dir]; each lands via a `.tmp` rename. */
    private fun extract(archive: File, dir: File, members: Map<String, RemoteFile>) {
        val remaining = members.toMutableMap()
        BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive), 1 shl 20)).use { bz ->
            TarArchiveInputStream(bz).use { tar ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val entry = tar.nextEntry ?: break
                    val wanted = remaining.remove(entry.name.substringAfterLast('/')) ?: continue
                    if (entry.isDirectory) continue
                    val tmp = File(dir, wanted.name + ".tmp")
                    tmp.outputStream().use { out ->
                        while (true) {
                            scope.ensureActive()
                            val n = tar.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                        }
                    }
                    val dest = File(dir, wanted.name)
                    dest.delete()
                    if (!tmp.renameTo(dest)) throw IOException("Couldn't move ${wanted.name} into place")
                    if (remaining.isEmpty()) break
                }
            }
        }
    }

    private fun stateFromDisk(model: SpeechModel): LocalModelState =
        if (isInstalled(model)) LocalModelState.Ready else LocalModelState.NotDownloaded(resumeBytes(model), model.downloadBytes)

    /** Bytes already on disk that a resumed download won't fetch again. */
    private fun resumeBytes(model: SpeechModel): Long {
        val dir = dir(model)
        return when (val s = model.source) {
            null -> 0L
            is ModelSource.Files -> s.files.sumOf { f ->
                val dest = File(dir, f.name)
                if (dest.length() == f.sizeBytes) f.sizeBytes else File(dir, f.name + ".part").length()
            }
            is ModelSource.Archive -> maxOf(File(dir, ARCHIVE_NAME).length(), File(dir, "$ARCHIVE_NAME.part").length())
        }
    }

    private fun set(id: String, state: LocalModelState) = _states.update { it + (id to state) }

    private companion object {
        const val TAG = "SpeechModels"
        const val ARCHIVE_NAME = "model.tar.bz2"
    }
}
