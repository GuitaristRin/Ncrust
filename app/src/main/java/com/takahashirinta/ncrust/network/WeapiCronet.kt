package com.takahashirinta.ncrust.network

import android.content.Context
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UploadDataProvider
import org.chromium.net.UploadDataSink
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Chromium 网络栈(Cronet)的同步 POST 助手——仅登录类请求使用。
 *
 * 为什么存在: music.163.com 的 WAF 按 TLS 指纹(JA3)检测, 非浏览器指纹
 * (OkHttp/JDK TLS、Android Conscrypt)的请求被静默吞掉(HTTP 200 + 空 body)。
 * PC 端实测: 同一批加密参数, Python requests/curl(OpenSSL 指纹)返回
 * {"code":200}, Java OkHttp(桌面 JDK 与 Android Conscrypt 两种)都拿到空 body。
 * Cronet 就是 Chromium 的网络栈, TLS ClientHello 与 Chrome 一致, 可绕过。
 *
 * 引擎来自 Play Services(play-services-cronet), 不打包 Chromium, 无包体成本;
 * 无 GMS 设备/引擎创建失败/拿到空 body 时返回 null, 由调用方回落 OkHttp。
 */
object WeapiCronet {

    /** 一次请求的结果: body 正文 + 全链路响应头(含重定向链, 登录 Set-Cookie 在这里)。 */
    class CronetResponse(val body: String, val headers: List<Pair<String, String>>)

    @Volatile private var engine: CronetEngine? = null
    // 同步等待场景: 回调直接投递到 Cronet 网络线程执行, 调用线程靠 latch 阻塞等结果。
    private val executor = Executor { it.run() }

    private fun engine(context: Context): CronetEngine =
        engine ?: synchronized(this) {
            engine ?: CronetEngine.Builder(context.applicationContext)
                .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISABLED, 0)
                .build()
                .also { engine = it }
        }

    /**
     * 同步 POST urlencoded body。成功返回 [CronetResponse];
     * 引擎不可用、超时、网络错误或 body 为空白(WAF 吞请求的典型形态)一律返回 null。
     */
    fun postForm(
        url: String,
        urlencodedBody: String,
        contentType: String = "application/x-www-form-urlencoded"
    ): CronetResponse? {
        val latch = CountDownLatch(1)
        val chunks = mutableListOf<ByteArray>()
        // 重定向链上每一跳的响应头都收——登录 Set-Cookie 可能在 302 那一跳。
        val headerList = mutableListOf<Pair<String, String>>()
        var failure: Throwable? = null

        val bytes = urlencodedBody.toByteArray(Charsets.UTF_8)
        val request = try {
            engine(RetrofitClient.appContext())
                .newUrlRequestBuilder(
                    url,
                    object : UrlRequest.Callback() {
                        override fun onRedirectReceived(
                            request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String
                        ) {
                            info.allHeadersAsList.forEach { headerList += it.key to it.value }
                            request.followRedirect()
                        }

                        override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                            info.allHeadersAsList.forEach { headerList += it.key to it.value }
                            request.read(ByteBuffer.allocateDirect(64 * 1024))
                        }

                        override fun onReadCompleted(
                            request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer
                        ) {
                            byteBuffer.flip()
                            val part = ByteArray(byteBuffer.remaining())
                            byteBuffer.get(part)
                            chunks += part
                            byteBuffer.clear()
                            request.read(byteBuffer)
                        }

                        override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                            latch.countDown()
                        }

                        override fun onFailed(
                            request: UrlRequest, info: UrlResponseInfo?, error: CronetException
                        ) {
                            failure = error
                            latch.countDown()
                        }

                        override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
                            latch.countDown()
                        }
                    },
                    executor
                )
                .addHeader("Content-Type", contentType)
                .addHeader(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
                .addHeader("Referer", "https://music.163.com/")
                .setHttpMethod("POST")
                .setUploadDataProvider(ByteArrayUploadProvider(bytes), executor)
                .build()
        } catch (e: Throwable) {
            android.util.Log.w("WeapiCronet", "engine/request build failed: ${e.javaClass.simpleName} ${e.message}")
            return null
        }

        try {
            request.start()
            if (!latch.await(25, TimeUnit.SECONDS)) request.cancel()
        } catch (e: Throwable) {
            android.util.Log.w("WeapiCronet", "post failed: ${e.javaClass.simpleName} ${e.message}")
            return null
        }
        failure?.let {
            android.util.Log.w("WeapiCronet", "onFailed: ${it.javaClass.simpleName} ${it.message}")
            return null
        }
        val body = if (chunks.isEmpty()) "" else chunks.reduce { a, b -> a + b }.toString(Charsets.UTF_8)
        // WAF 指纹拦截的标准形态就是 200 + 空 body——视为失败让调用方回落。
        return body.takeIf { it.isNotBlank() }?.let { CronetResponse(it, headerList) }
    }

    /**
     * 内存字节数组上传体(cronet-api 72 的 UploadDataProviders.create 等价物,
     * 手写一份避免版本间包位置差异: 72 里是顶层类, 新版才挪到 apihelpers 分包)。
     */
    private class ByteArrayUploadProvider(private val data: ByteArray) : UploadDataProvider() {
        private var position = 0

        override fun getLength(): Long = data.size.toLong()

        // cronet-api 72(play-services-cronet 18.1.0 传递依赖)的老签名不含 UrlRequest 参数。
        override fun read(sink: UploadDataSink, buffer: ByteBuffer) {
            val end = minOf(position + buffer.remaining(), data.size)
            buffer.put(data, position, end - position)
            position = end
            sink.onReadSucceeded(position == data.size)
        }

        override fun rewind(sink: UploadDataSink) {
            position = 0
            sink.onRewindSucceeded()
        }
    }
}
