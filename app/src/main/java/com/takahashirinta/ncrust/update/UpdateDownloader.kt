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
 * 应用内下载 APK：支持断点续传（HTTP Range）与取消。
 *
 * 断点续传是必需的而不是优化——GitHub 资产在国内经常慢到中途断流，每次失败都从头来
 * 就等于永远下不完。文件留在 `cacheDir/updates/`，续传时按已有字节数发
 * `Range: bytes=<已有>-`；服务器不认 Range（回 200）就删掉重下。
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
     * [onProgress] 的第二个参数为 0 表示总长未知（服务器没给 Content-Length）。
     */
    fun download(url: String, fileName: String, onProgress: (Long, Long) -> Unit): File {
        val target = File(directory, fileName)
        directory.mkdirs()
        val existing = if (target.isFile) target.length() else 0L
        val resumeFrom = if (existing > 0) existing else 0L

        var response = execute(url, resumeFrom)
        // 416：本地残留的字节数已经超过服务端那份文件（资产被换过）→ 清掉重来一次。
        if (response.code == 416 && resumeFrom > 0) {
            response.close()
            target.delete()
            response = execute(url, 0L)
        }
        response.use { resp ->
            if (cancelled) throw DownloadCancelledException()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("响应没有正文")

            val append = resp.code == 206 && resumeFrom > 0
            if (!append) target.delete()
            val contentLength = body.contentLength()
            val total = if (append && contentLength > 0) resumeFrom + contentLength
            else if (contentLength > 0) contentLength else 0L

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
            if (total > 0 && downloaded != total) {
                throw IOException("下载不完整：$downloaded/$total")
            }
            if (cancelled) throw DownloadCancelledException()
        }
        return target
    }

    private fun execute(url: String, resumeFrom: Long): okhttp3.Response {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UpdateService.USER_AGENT)
            .header("Accept", "application/octet-stream")
        if (resumeFrom > 0) builder.header("Range", "bytes=$resumeFrom-")
        val call = client.newCall(builder.build())
        currentCall = call
        return call.execute()
    }
}
