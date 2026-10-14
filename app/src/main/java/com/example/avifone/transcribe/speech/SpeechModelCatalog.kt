package com.example.avifone.transcribe.speech

/** Which sherpa-onnx API a model is loaded with. */
enum class SpeechModelKind { MOONSHINE, WHISPER, NEMOTRON }

/** One file of a model, fetched on its own (Hugging Face serves each file directly). */
data class RemoteFile(
    /** Name in the model directory once installed. */
    val name: String,
    val url: String,
    /** Exact size, checked after the download. */
    val sizeBytes: Long,
    /** Lower-case hex SHA-256, or null to skip the hash check. */
    val sha256: String?,
)

sealed interface ModelSource {
    /** Separate files; each resumes on its own. */
    data class Files(val files: List<RemoteFile>) : ModelSource

    /** One tar.bz2 (GitHub releases); only [members] are extracted. */
    data class Archive(
        val url: String,
        val sizeBytes: Long,
        val sha256: String,
        /** Archive member base name -> (installed name, size). */
        val members: Map<String, RemoteFile>,
    ) : ModelSource
}

/** A speech-to-text model: either bundled in the APK or downloaded into app storage. */
data class SpeechModel(
    val id: String,
    val displayName: String,
    val kind: SpeechModelKind,
    /** One line for the Settings card: languages and what it is good at. */
    val note: String,
    /** Null for the model bundled in the APK. */
    val source: ModelSource?,
    val license: String,
    /** Rough free-memory need while loaded, for a warning on small phones. */
    val minRamMb: Int,
) {
    val builtIn: Boolean get() = source == null

    /** Bytes that cross the network. */
    val downloadBytes: Long
        get() = when (val s = source) {
            null -> 0L
            is ModelSource.Files -> s.files.sumOf { it.sizeBytes }
            is ModelSource.Archive -> s.sizeBytes
        }

    /** Bytes on disk once installed. */
    val installedBytes: Long
        get() = when (val s = source) {
            null -> 0L
            is ModelSource.Files -> s.files.sumOf { it.sizeBytes }
            is ModelSource.Archive -> s.members.values.sumOf { it.sizeBytes }
        }

    /** The files that must exist in the model directory. */
    val installedFiles: List<RemoteFile>
        get() = when (val s = source) {
            null -> emptyList()
            is ModelSource.Files -> s.files
            is ModelSource.Archive -> s.members.values.toList()
        }

    /** Decimal megabytes, as download pages quote them. */
    val downloadMb: Int get() = Math.round(downloadBytes / 1_000_000.0).toInt()
}

/**
 * Models this app can run. Sizes and hashes come from the Hugging Face resolve URL's
 * `x-linked-size` / `x-linked-etag` headers, or from `sha256sum` of the GitHub tarball.
 * Everything here runs with the sherpa-onnx AAR already in the APK.
 */
object SpeechModelCatalog {

    private const val HF = "https://huggingface.co/csukuangfj"
    private const val GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

    // Both Whisper repositories ship the same multilingual tokens file.
    private const val WHISPER_TOKENS_SHA = "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"
    private const val WHISPER_TOKENS_SIZE = 816_730L

    /** Bundled in the APK (see speechPins in app/build.gradle.kts). Always available. */
    val MOONSHINE_TINY = SpeechModel(
        id = "moonshine-tiny",
        displayName = "Moonshine tiny",
        kind = SpeechModelKind.MOONSHINE,
        note = "English only. Fast and small; included in the app.",
        source = null,
        license = "MIT",
        minRamMb = 300,
    )

    /** NVIDIA Nemotron 3.5 streaming 0.6B, 560 ms chunks, int8. 40 locales including Hindi. */
    val NEMOTRON_3_5 = SpeechModel(
        id = "nemotron-3.5-560ms",
        displayName = "Nemotron 3.5 (0.6B)",
        kind = SpeechModelKind.NEMOTRON,
        note = "40 languages including English and Hindi. Good all-round accuracy.",
        source = ModelSource.Archive(
            url = "$GH/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11.tar.bz2",
            sizeBytes = 475_271_763L,
            sha256 = "c6bf5e0df765f9d5b43bc9e0536d4b4b3e7d40bdf5ecf13e45f134c51c05ae3a",
            members = mapOf(
                "encoder.int8.onnx" to RemoteFile("encoder.int8.onnx", "", 657_601_403L, null),
                "decoder.int8.onnx" to RemoteFile("decoder.int8.onnx", "", 14_978_075L, null),
                "joiner.int8.onnx" to RemoteFile("joiner.int8.onnx", "", 9_504_438L, null),
                "tokens.txt" to RemoteFile("tokens.txt", "", 131_440L, null),
            ),
        ),
        license = "NVIDIA Open Model License",
        minRamMb = 1_500,
    )

    /** OpenAI Whisper small, int8 (sherpa-onnx export). 99 languages. */
    val WHISPER_SMALL = SpeechModel(
        id = "whisper-small",
        displayName = "Whisper small",
        kind = SpeechModelKind.WHISPER,
        note = "99 languages including Hindi, Tamil and Telugu. Handles accents well.",
        source = ModelSource.Files(
            listOf(
                RemoteFile(
                    "encoder.int8.onnx", "$HF/sherpa-onnx-whisper-small/resolve/main/small-encoder.int8.onnx",
                    112_442_483L, "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9",
                ),
                RemoteFile(
                    "decoder.int8.onnx", "$HF/sherpa-onnx-whisper-small/resolve/main/small-decoder.int8.onnx",
                    262_226_114L, "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee",
                ),
                RemoteFile(
                    "tokens.txt", "$HF/sherpa-onnx-whisper-small/resolve/main/small-tokens.txt",
                    WHISPER_TOKENS_SIZE, WHISPER_TOKENS_SHA,
                ),
            )
        ),
        license = "MIT",
        minRamMb = 1_200,
    )

    /** OpenAI Whisper large-v3-turbo, int8. The most accurate; needs a recent phone. */
    val WHISPER_TURBO = SpeechModel(
        id = "whisper-turbo",
        displayName = "Whisper large-v3-turbo",
        kind = SpeechModelKind.WHISPER,
        note = "99 languages. Most accurate on accents; slow on older phones.",
        source = ModelSource.Files(
            listOf(
                RemoteFile(
                    "encoder.int8.onnx", "$HF/sherpa-onnx-whisper-turbo/resolve/main/turbo-encoder.int8.onnx",
                    674_716_297L, "b02dcdf54f348741e93fe732b67d933c8dcb6735655f710640143081db38878b",
                ),
                RemoteFile(
                    "decoder.int8.onnx", "$HF/sherpa-onnx-whisper-turbo/resolve/main/turbo-decoder.int8.onnx",
                    361_080_764L, "20accd02388482eb3a46bd615631adfdc85e1eb2c7db9ea3f02a40ffe6b81547",
                ),
                RemoteFile(
                    "tokens.txt", "$HF/sherpa-onnx-whisper-turbo/resolve/main/turbo-tokens.txt",
                    WHISPER_TOKENS_SIZE, WHISPER_TOKENS_SHA,
                ),
            )
        ),
        license = "MIT",
        minRamMb = 2_500,
    )

    val ALL: List<SpeechModel> = listOf(MOONSHINE_TINY, NEMOTRON_3_5, WHISPER_SMALL, WHISPER_TURBO)

    /** What a fresh install selects; it falls back to [MOONSHINE_TINY] until downloaded. */
    val DEFAULT: SpeechModel = NEMOTRON_3_5

    fun byId(id: String?): SpeechModel? = ALL.firstOrNull { it.id == id }
}

/** The language the recognizer is told to expect. */
enum class SpeechLanguage(val code: String, val displayName: String) {
    AUTO("auto", "Detect automatically"),
    ENGLISH("en", "English"),
    HINDI("hi", "Hindi");

    companion object {
        fun fromPref(value: String?): SpeechLanguage? = entries.firstOrNull { it.code == value }
    }
}
