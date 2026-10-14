package com.example.avifone.transcribe.llm

import android.content.Context
import android.util.Log
import com.example.avifone.transcribe.LocalEngine
import com.example.avifone.transcribe.LocalModelState
import com.example.avifone.transcribe.Summarizer
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Owns the on-device summary model: which engine this phone can use (Gemini Nano, or a
 * LiteRT-LM model file in app storage), the download of that file with resume, and the
 * loaded engine's lifetime. All public methods are called from the main thread; the
 * state flows may be updated from worker threads.
 */
class LocalModelManager(private val context: Context, private val scope: CoroutineScope) {

    /** The LiteRT-LM engine isn't thread-safe and a summary can take a while: its own thread. */
    val llmThread = Executors.newSingleThreadExecutor { Thread(it, "local-llm") }.asCoroutineDispatcher()

    val model = ModelCatalog.DEFAULT
    private val dir = File(context.filesDir, "models/llm")
    val modelFile = File(dir, model.fileName)
    private val partFile = File(dir, model.fileName + ".part")
    private val downloader = ResumableDownloader()

    private val _engine = MutableStateFlow(LocalEngine.NONE)
    val engine: StateFlow<LocalEngine> = _engine
    private val _state = MutableStateFlow<LocalModelState>(LocalModelState.Probing)
    val state: StateFlow<LocalModelState> = _state

    /** Display name of whatever LOCAL mode runs on this phone. */
    val modelName: String
        get() = if (_engine.value == LocalEngine.NANO) "Gemini Nano" else model.displayName

    private var probed = false
    private var downloadJob: Job? = null
    private var litert: LiteRtLmSummarizer? = null
    private var nano: NanoSummarizer? = null

    /** Finds out once whether Gemini Nano exists here; otherwise looks for the model file. */
    fun probe() {
        if (probed) return
        probed = true
        _state.value = LocalModelState.Probing
        scope.launch {
            val status = NanoSummarizer.status()
            if (status != null && status != FeatureStatus.UNAVAILABLE) {
                _engine.value = LocalEngine.NANO
                _state.value = if (status == FeatureStatus.AVAILABLE) {
                    LocalModelState.Ready
                } else {
                    LocalModelState.NotDownloaded(0, null)
                }
            } else {
                _engine.value = LocalEngine.LITERT
                refreshFromDisk()
            }
        }
    }

    private fun refreshFromDisk() {
        _state.value = if (modelFile.exists()) {
            LocalModelState.Ready
        } else {
            LocalModelState.NotDownloaded(partFile.length(), model.sizeBytes)
        }
    }

    fun download() {
        if (downloadJob?.isActive == true) return
        val current = _state.value
        if (current !is LocalModelState.NotDownloaded && current !is LocalModelState.Failed) return
        downloadJob = scope.launch {
            try {
                if (_engine.value == LocalEngine.NANO) downloadNano() else downloadLiteRt()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Model download failed", e)
                _state.value = LocalModelState.Failed(e.message ?: e.javaClass.simpleName, partFile.length())
            }
        }
    }

    private suspend fun downloadNano() {
        _state.value = LocalModelState.Downloading(0, null)
        NanoSummarizer.download().collect { status ->
            when (status) {
                is DownloadStatus.DownloadProgress ->
                    _state.value = LocalModelState.Downloading(status.totalBytesDownloaded, null)
                is DownloadStatus.DownloadCompleted -> _state.value = LocalModelState.Ready
                is DownloadStatus.DownloadFailed -> throw status.e
                else -> Unit
            }
        }
    }

    private suspend fun downloadLiteRt() {
        _state.value = LocalModelState.Downloading(partFile.length(), model.sizeBytes)
        downloader.download(model.url, modelFile, partFile, model.sizeBytes) { bytes, total ->
            _state.value = LocalModelState.Downloading(bytes, total ?: model.sizeBytes)
        }
        model.sha256?.let { expected ->
            _state.value = LocalModelState.Verifying
            val actual = withContext(Dispatchers.IO) { ResumableDownloader.sha256(modelFile) }
            if (actual != expected) {
                modelFile.delete()
                throw IOException("The downloaded file didn't match its checksum; try again")
            }
        }
        _state.value = LocalModelState.Ready
    }

    /** Stops the download; a LiteRT-LM part file is kept so it can resume later. */
    fun cancel() {
        val job = downloadJob ?: return
        downloadJob = null
        job.cancel()
        scope.launch {
            job.join()
            if (_engine.value == LocalEngine.LITERT) refreshFromDisk() else _state.value = LocalModelState.NotDownloaded(0, null)
        }
    }

    /** Removes the downloaded model file (LiteRT-LM only; Android owns Gemini Nano). */
    fun delete() {
        if (_engine.value != LocalEngine.LITERT) return
        val job = downloadJob
        cancel()
        releaseEngine()
        scope.launch {
            job?.join()
            withContext(Dispatchers.IO) {
                modelFile.delete()
                partFile.delete()
            }
            refreshFromDisk()
        }
    }

    /** The summarizer to use right now, or null while the model isn't ready. */
    fun summarizer(): Summarizer? {
        if (_state.value != LocalModelState.Ready) return null
        return when (_engine.value) {
            LocalEngine.NANO -> nano ?: NanoSummarizer().also { nano = it }
            LocalEngine.LITERT -> litert ?: LiteRtLmSummarizer(
                model = model,
                modelFile = modelFile,
                dispatcher = llmThread,
                cacheDir = File(context.cacheDir, "litertlm"),
            ).also { litert = it }
            LocalEngine.NONE -> null
        }
    }

    /** Frees the loaded LiteRT-LM engine (after any summary in flight on its thread). */
    fun releaseEngine() {
        val engine = litert ?: return
        litert = null
        scope.launch(llmThread) { engine.close() }
    }

    companion object {
        private const val TAG = "LocalModel"
    }
}
