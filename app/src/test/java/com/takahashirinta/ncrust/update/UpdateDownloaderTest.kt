package com.takahashirinta.ncrust.update

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Collections

/**
 * 下载器的断点续传行为，用一个真的本地 HTTP 服务打（不是 mock）：
 * 发什么 Range / If-Range、收到 206 / 200 / 416 各怎么落盘，只有真跑一遍才算数。
 *
 * 覆盖：整包下载、带元数据的续传（206）、服务端不认 Range（200，必须整体覆盖）、
 * 本地残留过大（416，清掉重来）、**无元数据 / 身份不符的残包按安全路径重下**、
 * **206 的 Content-Range 起点不匹配时不得拼接**、**If-Range 不符时整体覆盖**，
 * 以及「取消不算网络失败」。
 *
 * 用 `ServerSocket` 手写最小 HTTP/1.1 而不是 `com.sun.net.httpserver`：Android 单测的
 * 编译类路径里没有 `jdk.httpserver` 模块（android.jar 当 bootclasspath）。
 */
class UpdateDownloaderTest {

    private val body = ByteArray(1000) { (it % 251).toByte() }
    private val swappedBody = ByteArray(1200) { ((it * 7 + 3) % 251).toByte() }

    private lateinit var directory: File
    private lateinit var server: TinyHttpServer

    private val fileName = "ncrust-android-universal-1.4.0.apk"

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("ncrust-update-test").toFile()
        server = TinyHttpServer(body)
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
        directory.deleteRecursively()
    }

    private fun url(path: String) = "http://127.0.0.1:${server.port}$path"

    private fun target() = File(directory, fileName)

    /** 写一份与当前请求身份吻合的续传元数据，等价于「上次下载到一半时留下的残包」。 */
    private fun writeMarker(
        identity: String = url("/asset.apk"),
        size: Long = 0L,
        etag: String? = null
    ) {
        File(directory, "$fileName.identity").writeText(
            ResumeMarker(url("/asset.apk"), identity, size, etag).encode()
        )
    }

    @Test
    fun `downloads the whole file and reports progress`() {
        val progress = mutableListOf<Pair<Long, Long>>()
        val file = UpdateDownloader(directory).download(url("/asset.apk"), fileName) { done, total ->
            progress += done to total
        }

        assertArrayEquals(body, file.readBytes())
        assertNull("首次下载不该带 Range", server.rangeHeaders.single())
        assertEquals(body.size.toLong(), progress.last().first)
        assertEquals(body.size.toLong(), progress.last().second)
    }

    @Test
    fun `resumes from the bytes already on disk when the identity matches`() {
        target().writeBytes(body.copyOfRange(0, 400))
        writeMarker()

        val file = UpdateDownloader(directory).download(url("/asset.apk"), fileName) { _, _ -> }

        assertEquals("bytes=400-", server.rangeHeaders.single())
        assertArrayEquals("续传后必须是完整包，不能少前面 400 字节", body, file.readBytes())
    }

    @Test
    fun `restarts when the server ignores the range request`() {
        server.honorRange = false
        target().writeBytes(ByteArray(400) { 0x7F })
        writeMarker()

        val file = UpdateDownloader(directory).download(url("/asset.apk"), fileName) { _, _ -> }

        assertEquals("bytes=400-", server.rangeHeaders.single())
        assertArrayEquals("服务端回 200 时必须整体覆盖，不能把旧字节拼在前面", body, file.readBytes())
    }

    @Test
    fun `clears a stale partial file when the server answers 416`() {
        // 本地残留比服务端那份还大（班次里的资产被换过）：416 → 删掉重下。
        target().writeBytes(ByteArray(2000) { 0x11 })
        writeMarker(size = 2000L)

        val file = UpdateDownloader(directory).download(url("/asset.apk"), fileName) { _, _ -> }

        assertEquals(listOf("bytes=2000-", null), server.rangeHeaders)
        assertArrayEquals(body, file.readBytes())
    }

    @Test
    fun `redownloads a legacy partial file that has no metadata`() {
        // 早期版本留下的残包没有元数据：身份无从校验 → 只能安全重下，绝不拼接。
        target().writeBytes(ByteArray(400) { 0x7F })

        val file = UpdateDownloader(directory).download(url("/asset.apk"), fileName) { _, _ -> }

        assertNull("无元数据残包必须整包重下，不发 Range", server.rangeHeaders.single())
        assertArrayEquals(body, file.readBytes())
    }

    @Test
    fun `redownloads when the asset identity changed under the same name`() {
        // 同名原地换包：URL / 文件名不变，但资产身份换了。
        target().writeBytes(body.copyOfRange(0, 400))
        File(directory, "$fileName.identity").writeText(
            ResumeMarker(url("/asset.apk"), "asset-1", body.size.toLong(), null).encode()
        )
        server.body = swappedBody

        val file = UpdateDownloader(directory).download(
            url = url("/asset.apk"),
            fileName = fileName,
            identity = "asset-2",
            expectedSize = swappedBody.size.toLong()
        ) { _, _ -> }

        assertNull("身份变了就不能续传", server.rangeHeaders.single())
        assertArrayEquals("必须拿到换包后的完整新包", swappedBody, file.readBytes())
    }

    @Test
    fun `restarts when the 206 Content-Range start does not match the request`() {
        target().writeBytes(body.copyOfRange(0, 400))
        writeMarker()
        server.mangleRangeStart = true

        val file = UpdateDownloader(directory).download(url("/asset.apk"), fileName) { _, _ -> }

        // 第一次带 Range、被谎报起点 → 清掉重下（第二次不带 Range）。
        assertEquals(listOf("bytes=400-", null), server.rangeHeaders)
        assertArrayEquals("谎报 Content-Range 的残包必须丢弃", body, file.readBytes())
    }

    @Test
    fun `redownloads when If-Range does not match`() {
        target().writeBytes(body.copyOfRange(0, 400))
        writeMarker(etag = "old-etag")
        server.etag = "new-etag"
        server.honorIfRange = true

        val file = UpdateDownloader(directory).download(url("/asset.apk"), fileName) { _, _ -> }

        assertEquals("old-etag", server.ifRangeHeaders.single())
        assertArrayEquals("If-Range 不符时服务端回 200，必须整体覆盖", body, file.readBytes())
    }

    @Test
    fun `resumes when If-Range matches`() {
        target().writeBytes(body.copyOfRange(0, 400))
        writeMarker(etag = "same-etag")
        server.etag = "same-etag"
        server.honorIfRange = true

        val file = UpdateDownloader(directory).download(url("/asset.apk"), fileName) { _, _ -> }

        assertEquals("same-etag", server.ifRangeHeaders.single())
        assertEquals("bytes=400-", server.rangeHeaders.single())
        assertArrayEquals(body, file.readBytes())
    }

    @Test
    fun `a cancel is not reported as a network failure`() {
        val downloader = UpdateDownloader(directory)
        downloader.cancel()

        assertThrows(DownloadCancelledException::class.java) {
            downloader.download(url("/asset.apk"), fileName) { _, _ -> }
        }
    }

    @Test
    fun `creates the target directory when missing`() {
        val nested = File(directory, "updates")
        val file = UpdateDownloader(nested).download(url("/asset.apk"), fileName) { _, _ -> }

        assertTrue(nested.isDirectory)
        assertArrayEquals(body, file.readBytes())
    }

    /**
     * 最小 HTTP/1.1 服务：GET 就回整份 body（或按 Range 回 206 / 416）。
     * 每个响应都带 `Connection: close` 并关掉 socket，省得处理 keep-alive。
     */
    private class TinyHttpServer(initialBody: ByteArray) {

        private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        private val thread = Thread(::loop).apply { isDaemon = true }

        /** 可换：模拟同名原地换包。 */
        @Volatile
        var body: ByteArray = initialBody

        /** 服务端当前内容的 ETag（null = 不发 ETag / 不认 If-Range）。 */
        @Volatile
        var etag: String? = null

        val rangeHeaders: MutableList<String?> = Collections.synchronizedList(mutableListOf())
        val ifRangeHeaders: MutableList<String?> = Collections.synchronizedList(mutableListOf())

        /** false = 假装不认 Range（永远回 200 + 整包），用来验证客户端会整体覆盖。 */
        @Volatile
        var honorRange = true

        /** true = 回 206 时谎报 Content-Range 起点，用来验证客户端不拼接不可信的残包。 */
        @Volatile
        var mangleRangeStart = false

        /** true = 认 If-Range：与当前 ETag 不符就回 200 + 整包。 */
        @Volatile
        var honorIfRange = false

        val port: Int get() = socket.localPort

        fun start() = thread.start()

        fun stop() {
            runCatching { socket.close() }
        }

        private fun loop() {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    return
                }
                runCatching { handle(client) }
            }
        }

        private fun handle(client: Socket) {
            client.use { s ->
                val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                if (reader.readLine() == null) return
                var range: String? = null
                var ifRange: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) {
                        range = line.substringAfter(':').trim()
                    } else if (line.startsWith("If-Range:", ignoreCase = true)) {
                        ifRange = line.substringAfter(':').trim()
                    }
                }
                rangeHeaders += range
                ifRangeHeaders += ifRange

                val serverBody = body
                var start = if (honorRange) {
                    range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                } else {
                    0
                }
                // If-Range 与当前 ETag 不符 → 服务端忽略 Range，回整包。
                if (honorIfRange && ifRange != null && (etag == null || ifRange != etag)) {
                    start = 0
                }

                val out = s.getOutputStream()
                if (honorRange && start >= serverBody.size) {
                    out.write(
                        ("HTTP/1.1 416 Range Not Satisfiable\r\n" +
                            "Content-Length: 0\r\nConnection: close\r\n\r\n")
                            .toByteArray(Charsets.ISO_8859_1)
                    )
                    out.flush()
                    return
                }

                val slice = serverBody.copyOfRange(start, serverBody.size)
                val reportedStart = if (start > 0 && mangleRangeStart) start + 7 else start
                val header = buildString {
                    append(if (start > 0) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                    append("Content-Type: application/octet-stream\r\n")
                    append("Content-Length: ${slice.size}\r\n")
                    if (etag != null) append("ETag: $etag\r\n")
                    if (start > 0) {
                        append("Content-Range: bytes $reportedStart-${serverBody.size - 1}/${serverBody.size}\r\n")
                    }
                    append("Connection: close\r\n\r\n")
                }
                out.write(header.toByteArray(Charsets.ISO_8859_1))
                out.write(slice)
                out.flush()
            }
        }
    }
}
