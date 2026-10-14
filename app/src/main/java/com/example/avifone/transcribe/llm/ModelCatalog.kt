package com.example.avifone.transcribe.llm

/** An LLM file that LiteRT-LM can run, downloaded into app storage on first use. */
data class LlmModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    /** Exact size, checked after the download. */
    val sizeBytes: Long,
    /** Lower-case hex SHA-256 of the file, or null to skip the hash check. */
    val sha256: String?,
    val contextTokens: Int,
    val license: String,
) {
    /** Decimal megabytes, as download pages quote them. */
    val sizeMb: Int get() = Math.round(sizeBytes / 1_000_000.0).toInt()
}

/**
 * Models hosted by the LiteRT community on Hugging Face. Only ungated repositories work
 * here, since the phone downloads anonymously (Gemma 3 needs a licence click and a token).
 * Size and hash come from the `x-linked-size` / `x-linked-etag` headers of the resolve URL.
 */
object ModelCatalog {

    /** Qwen3 0.6B, dynamic INT4: the smallest file with a 4096-token context. */
    val DEFAULT = LlmModel(
        id = "qwen3-0.6b-int4",
        displayName = "Qwen3 0.6B",
        fileName = "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
        url = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
        sizeBytes = 344_671_744L,
        sha256 = "03e7da1eb1108b50dffaa9bb52cc7bcbad2eb0c66ca990267f480c1e545d2856",
        contextTokens = 4096,
        license = "Apache-2.0",
    )

    // Larger alternatives, same repository family, for phones with more RAM:
    //   Qwen3-0.6B.litertlm (INT8, 614 MB, 4096 ctx)
    //     sha256 c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108
    //   litert-community/Qwen2.5-1.5B-Instruct  (q8, 1.6 GB)
    //   litert-community/gemma-4-E2B-it-litert-lm (gemma-4-E2B-it-gpu.litertlm, 2.0 GB, Apache-2.0)
}
