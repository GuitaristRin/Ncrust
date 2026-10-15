package com.takahashirinta.ncrust.update

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 用户取消下载（与网络失败区分开：取消不该提示「下载失败」）。 */
class DownloadCancelledException : IOException("下载已取消")

/**
 * 断点续传的元数据（**纯数据 + 纯编解码**，便于单测）。
 *
 * 只凭文件名 + 本地长度判定续传是不够的：`docs/RELEASES.md` 明确允许**同名资产
 * 原地替换**，旧包的前缀接上新包的后缀会拼出坏包，而长度检查还可能刚好通过。
 * 所以续传必须绑定到稳定的资产身份（GitHub 资产 id / URL）与期望总长，
 * 身份不符就当作没有残包，安全重下。
 */
internal data class ResumeMarker(
    val url: String,
    val identity: String,
    val expectedSize: Long,
    val etag: String?
) {
    /** 只有 URL、身份一致，且双方都知道的总长相同时才允许续传。 */
    fun matches(url: String, identity: String, expectedSize: Long): Boolean {
        if (this.url != url) return false
        if (this.identity != identity) return false
        if (this.expectedSize > 0L && expectedSize > 0L && this.expectedSize != expectedSize) return false
        return true
    }

    fun encode(): String = buildString {
        append("version=1\n")
        append("url=").append(url).append('\n')
        append("identity=").append(identity).append('\n')
        append("size=").append(expectedSize).append('\n')
        append("etag=").append(etag.orEmpty()).append('\n')
    }

    companion object {
        fun decode(text: String?): ResumeMarker? {
            if (text.isNullOrBlank()) return null
            val map = HashMap<String, String>()
            for (line in text.lineSequence()) {
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                map[line.substring(0, eq)] = line.substring(eq + 1)
            }
            if (map["version"] != "1") return null
            val url = map["url"] ?: return null
            val identity = map["identity"] ?: return null
            val size = map["size"]?.toLongOrNull() ?: return null
            val etag = map["etag"]?.takeIf { it.isNotEmpty() }
            return ResumeMarker(url, identity, size, etag)
        }
    }
}

/** HTTP `Content-Range` 的解析结果；[total] 为 -1 表示服务端用 `*` 表示总量未知。 */
internal data class ContentRange(val start: Long, val end: Long, val total: Long)

/** 解析 `bytes <start>-<end>/<total>`；非法 / 缺失返回 null。 */
internal fun parseContentRange(header: String?): ContentRange? {
    if (header.isNullOrBlank()) return null
    val value = header.trim()
    if (!value.startsWith("bytes", ignoreCase = true)) return null
    val rest = value.substringAfter(' ').trim()
    val slash = rest.indexOf('/')
    if (slash <= 0) return null
    val rangePart = rest.substring(0, slash)
    val totalPart = rest.substring(slash + 1).trim()
    val dash = rangePart.indexOf('-')
    if (dash <= 0) return null
    val start = rangePart.substring(0, dash).trim().toLongOrNull() ?: return null
    val end = rangePart.substring(dash + 1).trim().toLongOrNull() ?: return null
    val total = if (totalPart == "*") -1L else totalPart.toLongOrNull() ?: return null
    return ContentRange(start, end, total)
}

/**
 * 应用内下载 APK：支持断点续传（HTTP Range）、取消，以及**基于资产身份**的安全续传。
 *
 * 断点续传是必需的而不是优化——GitHub 资产在国内经常慢到中途断流，每次失败都从头来
 * 就等于永远下不完。规则：
 *  - 残包旁边留一份 [ResumeMarker]；只有 URL / 资产身份 / 期望总长都吻合才续传，
 *    否则（含**早期没有元数据的残包**）删掉残包从头下；
 *  - 续传时带 `If-Range`（存下来的 ETag）：资产被换过、服务端回 200 就整体覆盖；
 *  - 服务端回 206 时必须校验 `Content-Range` 的起点正是请求的续传点、总量与期望一致，
 *    否则残包不可信，清掉重下——最多重试 [MAX_RESTARTS] 次，绝不陷入坏前缀死循环。
 *
 * GitHub 资产会 302 到 `objects.githubusercontent.com`，OkHttp 默认跟随重定向。
 */
class UpdateDownloader(private val directory: File) {

    // 独立 client：绝不能复用 RetrofitClient 的（那个会往请求里塞网易云 cookie，
    // 把登录态发给 GitHub 是不可接受的）。
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var currentCall: okhttp3.Call? = null

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
        runCatching { currentCall?.cancel() }
    }

    /**
     * 下载到 `directory/<fileName>`，返回落盘文件。
     * [identity] 是稳定资产身份（资产 id / URL），决定本地残包能否被续传；
     * [expectedSize] 是期望总长（0 表示未知）。
     * [onProgress] 的第二个参数为 0 表示总长未知（服务器没给 Content-Length）。
     */
    fun download(
        url: String,
        fileName: String,
        identity: String = url,
        expectedSize: Long = 0L,
        onProgress: (Long, Long) -> Unit
    ): File {
        directory.mkdirs()
        val target = File(directory, fileName)
        val markerFile = File(directory, markerNameFor(fileName))

        // 1. 决定本地残包能否续传：有字节、有元数据、身份完全吻合，三者缺一不可。
        val stored = readMarker(markerFile)
        val localBytes = if (target.isFile) target.length() else 0L
        val canResume = localBytes > 0L && stored != null && stored.matches(url, identity, expectedSize)
        if (localBytes > 0L && !canResume) {
            target.delete() // 无元数据 / 身份不符的旧残包：安全重下，绝不拼接
        }
        markerFile.delete()

        var resumeFrom = if (canResume) localBytes else 0L
        var ifRange = if (canResume) stored?.etag else null
        var restarts = 0

        while (true) {
            if (cancelled) throw DownloadCancelledException()
            var restart = false
            execute(url, resumeFrom, ifRange).use { response ->
                if (cancelled) throw DownloadCancelledException()

                if (response.code == 416 && resumeFrom > 0L) {
                    // 本地残留已经超过服务端那份（资产被换过）→ 清掉重来。
                    target.delete()
                    resumeFrom = 0L
                    ifRange = null
                    restart = true
                    return@use
                }
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("响应没有正文")

                val append = response.code == 206 && resumeFrom > 0L
                val range = if (append) parseContentRange(response.header("Content-Range")) else null
                if (append) {
                    val totalOk = expectedSize <= 0L ||
                        range == null ||
                        range.total <= 0L ||
                        range.total == expectedSize
                    // 206 的起点必须正是请求的续传点；总量若双方都知道也要吻合。
                    if (range == null || range.start != resumeFrom || !totalOk) {
                        target.delete()
                        resumeFrom = 0L
                        ifRange = null
                        restart = true
                        return@use
                    }
                }

                if (!append) {
                    target.delete() // 200：整体覆盖
                    resumeFrom = 0L
                }

                val contentLength = body.contentLength()
                val total = when {
                    append && range != null && range.total > 0L -> range.total
                    append && contentLength > 0L -> resumeFrom + contentLength
                    contentLength > 0L -> contentLength
                    else -> 0L
                }

                var downloaded = if (append) resumeFrom else 0L
                onProgress(downloaded, total)
                FileOutputStream(target, append).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    body.byteStream().use { input ->
                        while (true) {
                            if (cancelled) throw DownloadCancelledException()
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                    out.flush()
                }
                // 服务器给了总长却没收满 → 传输被截断，别把半截 APK 当成功。
                if (total > 0L && downloaded != total) {
                    throw IOException("下载不完整：$downloaded/$total")
                }
                if (cancelled) throw DownloadCancelledException()

                writeMarker(markerFile, ResumeMarker(url, identity, expectedSize, response.header("ETag")))
                return target
            }
            if (!restart) throw IOException("下载状态异常")
            restarts++
            if (restarts > MAX_RESTARTS) throw IOException("续传多次失败")
        }
    }

    private fun execute(url: String, resumeFrom: Long, ifRange: String?): okhttp3.Response {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UpdateService.USER_AGENT)
            .header("Accept", "application/octet-stream")
        if (resumeFrom > 0L) {
            builder.header("Range", "bytes=$resumeFrom-")
            if (!ifRange.isNullOrBlank()) builder.header("If-Range", ifRange)
        }
        val call = client.newCall(builder.build())
        currentCall = call
        return call.execute()
    }

    private fun markerNameFor(fileName: String): String = "$fileName.identity"

    private fun readMarker(file: File): ResumeMarker? {
        if (!file.isFile) return null
        return ResumeMarker.decode(runCatching { file.readText() }.getOrNull())
    }

    private fun writeMarker(file: File, marker: ResumeMarker) {
        runCatching { file.writeText(marker.encode()) }
    }

    private companion object {
        /** 续传被服务端 / 内容变动打断后，允许的最多重下次数。 */
        const val MAX_RESTARTS = 2
    }
}
