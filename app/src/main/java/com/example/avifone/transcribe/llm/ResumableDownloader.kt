package com.example.avifone.transcribe.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * Downloads one large file with plain [HttpURLConnection], resuming a partial `.part` file
 * through an HTTP `Range` request. Cancelling the coroutine keeps the part for next time.
 */
class ResumableDownloader(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) {

    /**
     * Fetches [url] into [dest] via [part]. [onProgress] gets (bytes so far, total or null)
     * a few times per second. When [expectedBytes] is given, a different final size fails.
     */
    suspend fun download(
        url: String,
        dest: File,
        part: File,
        expectedBytes: Long?,
        onProgress: (bytes: Long, total: Long?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        part.parentFile?.mkdirs()
        var restarted = false
        while (true) {
            val have = part.length()
            val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            try {
                val code = conn.responseCode
                val append: Boolean
                val total: Long?
                when {
                    code == HttpURLConnection.HTTP_PARTIAL && have > 0 -> {
                        append = true
                        total = contentRangeTotal(conn.getHeaderField("Content-Range")) ?: expectedBytes
                    }
                    code == HttpURLConnection.HTTP_OK -> {
                        // The server ignored the range (or there was none): start over.
                        append = false
                        total = conn.contentLengthLong.takeIf { it > 0 } ?: expectedBytes
                    }
                    code == 416 && !restarted -> {
                        // Our part is longer than the file: throw it away and retry once.
                        part.delete()
                        restarted = true
                        continue
                    }
                    else -> throw IOException("HTTP $code while downloading ${dest.name}")
                }
                var done = if (append) have else 0L
                var lastReport = 0L
                onProgress(done, total)
                FileOutputStream(part, append).use { out ->
                    conn.inputStream.use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastReport >= 250) {
                                lastReport = now
                                onProgress(done, total)
                            }
                        }
                    }
                }
                onProgress(done, total)
                break
            } finally {
                conn.disconnect()
            }
        }
        val size = part.length()
        if (expectedBytes != null && size != expectedBytes) {
            part.delete()
            throw IOException("Downloaded $size bytes, expected $expectedBytes")
        }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) throw IOException("Couldn't move ${dest.name} into place")
    }

    companion object {
        /** "bytes 100-999/1000" -> 1000; null when the total is unknown ("*") or malformed. */
        fun contentRangeTotal(header: String?): Long? =
            header?.substringAfter('/', "")?.trim()?.toLongOrNull()

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
