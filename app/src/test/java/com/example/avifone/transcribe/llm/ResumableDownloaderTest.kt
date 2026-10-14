package com.example.avifone.transcribe.llm

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress

class ResumableDownloaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private val data = ByteArray(1_000_000) { (it * 31 + it / 7).toByte() }
    private lateinit var server: HttpServer
    private var honourRange = true
    private var lastRange: String? = null

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/model") { ex ->
            lastRange = ex.requestHeaders.getFirst("Range")
            val from = lastRange?.takeIf { honourRange }?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
            when {
                from == null -> {
                    ex.sendResponseHeaders(200, data.size.toLong())
                    ex.responseBody.use { it.write(data) }
                }
                from >= data.size -> {
                    ex.sendResponseHeaders(416, -1)
                    ex.close()
                }
                else -> {
                    ex.responseHeaders.add("Content-Range", "bytes $from-${data.size - 1}/${data.size}")
                    ex.sendResponseHeaders(206, (data.size - from).toLong())
                    ex.responseBody.use { it.write(data, from, data.size - from) }
                }
            }
        }
        server.start()
    }

    @After
    fun stopServer() = server.stop(0)

    private fun url() = "http://127.0.0.1:${server.address.port}/model"
    private fun dest() = File(tmp.root, "model.bin")
    private fun part() = File(tmp.root, "model.bin.part")

    @Test
    fun downloadsFromScratch() = runBlocking {
        val progress = mutableListOf<Pair<Long, Long?>>()
        ResumableDownloader().download(url(), dest(), part(), data.size.toLong()) { b, t -> progress += b to t }
        assertArrayEquals(data, dest().readBytes())
        assertFalse(part().exists())
        assertEquals(data.size.toLong(), progress.last().first)
        assertEquals(data.size.toLong(), progress.last().second)
    }

    @Test
    fun resumesFromAPartFile() = runBlocking {
        part().writeBytes(data.copyOfRange(0, 400_000))
        ResumableDownloader().download(url(), dest(), part(), data.size.toLong()) { _, _ -> }
        assertEquals("bytes=400000-", lastRange)
        assertArrayEquals(data, dest().readBytes())
    }

    @Test
    fun startsOverWhenTheServerIgnoresTheRange() = runBlocking {
        honourRange = false
        part().writeBytes(ByteArray(400_000) { 1 })  // garbage that must not survive
        ResumableDownloader().download(url(), dest(), part(), data.size.toLong()) { _, _ -> }
        assertArrayEquals(data, dest().readBytes())
    }

    @Test
    fun discardsAPartLongerThanTheFile() = runBlocking {
        part().writeBytes(ByteArray(data.size + 10))
        ResumableDownloader().download(url(), dest(), part(), data.size.toLong()) { _, _ -> }
        assertArrayEquals(data, dest().readBytes())
    }

    @Test
    fun failsOnSizeMismatch() = runBlocking {
        try {
            ResumableDownloader().download(url(), dest(), part(), data.size.toLong() + 1) { _, _ -> }
            fail("expected an IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("expected"))
        }
        assertFalse(dest().exists())
        assertFalse(part().exists())
    }

    @Test
    fun contentRangeTotalParses() {
        assertEquals(1000L, ResumableDownloader.contentRangeTotal("bytes 100-999/1000"))
        assertEquals(null, ResumableDownloader.contentRangeTotal("bytes 100-999/*"))
        assertEquals(null, ResumableDownloader.contentRangeTotal(null))
    }

    @Test
    fun sha256MatchesAKnownValue() {
        val f = File(tmp.root, "abc.txt").apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ResumableDownloader.sha256(f))
    }
}
