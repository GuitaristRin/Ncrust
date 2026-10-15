package com.takahashirinta.ncrust.walkman.smoke

import android.app.Activity
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import com.takahashirinta.ncrust.walkman.R
import com.takahashirinta.ncrust.walkman.api.NcmApi
import com.takahashirinta.ncrust.walkman.crypto.Aes
import com.takahashirinta.ncrust.walkman.crypto.X25519
import com.takahashirinta.ncrust.walkman.net.Tls
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * W0 冒烟界面：一个按钮 + 一块日志。
 *
 * 存在的意义是"能在这台 4.1 机器上把匿名取链与 MediaPlayer 播放走通并看见每一步"，
 * 不做任何产品 UI —— W3 才做界面。
 *
 * 每一步都同时进 logcat 与界面：界面是给现场看的，logcat 是给 `adb logcat -s NcrustWm`
 * 留证的。TLS 版本、业务码、码率三项是 spike 里最需要盯的（`docs/f885-spike-report.md`）。
 *
 * 可用 `am start -n com.takahashirinta.ncrust.walkman/.smoke.SmokeActivity --ez auto true`
 * 免点击触发（自动化冒烟用）。
 *
 * **线程模型**：网络与 MediaPlayer 的创建、异步准备、回调、轮询、释放全部在同一个
 * HandlerThread 上执行。Activity 销毁只发送停止消息，不跨线程释放正在准备的播放器。
 */
class SmokeActivity : Activity() {

    private lateinit var button: Button
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private val main = Handler(Looper.getMainLooper())
    private val buffer = StringBuilder()
    private val workerThread = HandlerThread("ncrust-wm-smoke").apply { start() }
    private val worker = Handler(workerThread.looper)

    @Volatile
    private var running = false

    @Volatile
    private var destroyed = false

    // 只由 worker 访问。
    private var player: MediaPlayer? = null
    private var secondsPlayed = 0
    private var initialPosition = 0
    private var positionAdvanced = false

    private val prepareTimeout = Runnable {
        log("FAIL: MediaPlayer prepare timed out")
        finishRun()
    }

    private val progressTick = object : Runnable {
        override fun run() {
            if (destroyed) {
                finishRun()
                return
            }
            val mp = player ?: return
            try {
                val position = mp.currentPosition
                secondsPlayed++
                positionAdvanced = positionAdvanced || position > initialPosition
                log("t+${secondsPlayed}s position=${position}ms playing=${mp.isPlaying}")
                if (secondsPlayed >= PLAY_SECONDS) {
                    log(if (positionAdvanced) "PASS: playback position advanced" else "FAIL: playback position did not advance")
                    finishRun()
                } else {
                    worker.postDelayed(this, 1000)
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_smoke)
        button = findViewById(R.id.btn_run)
        logView = findViewById(R.id.log)
        scroll = findViewById(R.id.scroll)

        button.setOnClickListener { start() }
        log("device: ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        log("tls: ${Tls.installResult ?: "not installed yet"}")

        if (intent?.getBooleanExtra(EXTRA_AUTO, false) == true) {
            log("auto trigger from intent")
            start()
        }
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        worker.post {
            finishRun()
            // quitSafely 是 API 18；API 16 用 quit，已在本线程释放完播放器。
            workerThread.quit()
        }
        super.onDestroy()
    }

    private fun start() {
        if (running || destroyed) {
            log("already running")
            return
        }
        running = true
        button.isEnabled = false
        buffer.setLength(0)
        logView.text = ""
        worker.post { runSmoke() }
    }

    // ---------------------------------------------------------------- 冒烟主流程

    private fun runSmoke() {
        try {
            check(Tls.available) { "Conscrypt is not available" }
            verifyRuntimeCrypto()
            val songId = SONG_ID
            log("--- song detail $songId ---")
            val song = NcmApi.songDetail(listOf(songId)).firstOrNull()
            if (song == null) {
                log("FAIL: 歌曲详情为空")
                finishRun()
                return
            }
            log("song: ${song.name} / ${song.artists} / ${song.album} / ${song.durationMs}ms")
            log("privilege: ${song.privilege}")

            log("--- song url (standard) ---")
            val result = NcmApi.songUrl(
                songId = songId,
                level = REQUEST_LEVEL,
                privilege = song.privilege,
                sessionValid = false
            )
            log("tried levels: ${result.triedLevels.joinToString(",")}")
            log("business code (last): ${result.lastCode}")
            log("tls version: ${result.tlsVersion}")
            log("resolved level: ${result.resolvedLevel}  bitrate: ${result.item?.br ?: 0} bps")
            log("verdict: ${result.verdict}")

            val url = result.url
            if (url.isNullOrEmpty()) {
                log("FAIL: 阶梯走完仍没有可播地址（lastCode=${result.lastCode}）")
                finishRun()
                return
            }
            log("url: $url")

            if (destroyed) finishRun() else play(url)
        } catch (e: Exception) {
            fail(e)
        }
    }

    /** JVM 夹具无法覆盖 API 16 的 IvParameterSpec 分支，在设备上做一次本地往返验证。 */
    private fun verifyRuntimeCrypto() {
        val random = SecureRandom()
        val key = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val plain = "Ncrust Walkman crypto smoke".toByteArray(Charsets.UTF_8)
        val encrypted = Aes.gcmEncrypt(key, iv, plain)
        check(Aes.gcmDecrypt(key, iv, encrypted).contentEquals(plain)) { "AES-GCM round trip failed" }

        val started = System.currentTimeMillis()
        val alice = ByteArray(32).also { random.nextBytes(it) }
        val bob = ByteArray(32).also { random.nextBytes(it) }
        val shared = X25519.x25519(alice, X25519.publicKeyFromPrivate(bob))
        val peer = X25519.x25519(bob, X25519.publicKeyFromPrivate(alice))
        check(shared.any { it != 0.toByte() } && shared.contentEquals(peer)) { "X25519 agreement failed" }
        log("crypto: AES-GCM round trip and X25519 agreement ok; X25519 four operations=${System.currentTimeMillis() - started}ms; RNG=${random.provider.name}")
    }

    /**
     * 用系统 `MediaPlayer` 播放。
     *
     * 刻意不引 ExoPlayer / FFmpeg：`TASKBOOK.md` §0 要求保留原厂音频通路
     * （S-Master HX、DSEE、ClearAudio 只在系统播放器这条路上生效）。
     */
    // setAudioStreamType 在 API 26 被 AudioAttributes 取代，但本工程 minSdk 是 16、
    // 设备是 4.1：AudioAttributes 是 API 21 才有的类，在 4.1 上引用它会 NoClassDefFoundError。
    // 这里就是"必须用老 API"的场景，抑制告警而不是换个跑不起来的写法。
    @Suppress("DEPRECATION")
    private fun play(url: String) {
        log("--- MediaPlayer ---")
        val mp = MediaPlayer()
        player = mp
        mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
        mp.setOnErrorListener { _, what, extra ->
            log("FAIL: onError what=$what extra=$extra")
            finishRun()
            true
        }
        mp.setOnCompletionListener {
            log("onCompletion")
            finishRun()
        }

        val prepareStart = System.currentTimeMillis()
        mp.setOnPreparedListener {
            worker.removeCallbacks(prepareTimeout)
            if (destroyed) {
                finishRun()
            } else {
                try {
                    log("prepare ok in ${System.currentTimeMillis() - prepareStart}ms; duration=${mp.duration}ms")
                    initialPosition = mp.currentPosition
                    secondsPlayed = 0
                    positionAdvanced = false
                    mp.start()
                    log("start ok; isPlaying=${mp.isPlaying}")
                    worker.postDelayed(progressTick, 1000)
                } catch (e: Exception) {
                    fail(e)
                }
            }
        }

        mp.setDataSource(url)
        log("setDataSource ok")

        mp.prepareAsync()
        worker.postDelayed(prepareTimeout, 30_000)
    }

    private fun releasePlayer() {
        val mp = player ?: return
        player = null
        runCatching { mp.release() }
    }

    private fun finishRun() {
        worker.removeCallbacks(prepareTimeout)
        worker.removeCallbacks(progressTick)
        releasePlayer()
        running = false
        if (!destroyed) main.post { if (!destroyed) button.isEnabled = true }
    }

    private fun fail(error: Exception) {
        log("FAIL: ${error.javaClass.simpleName}: ${error.message}")
        Log.e(Tls.TAG, "smoke failed", error)
        finishRun()
    }

    // ---------------------------------------------------------------- 日志

    /** 同时写 logcat 与界面。界面部分必须回主线程（只有主线程能碰 View）。 */
    private fun log(message: String) {
        Log.i(Tls.TAG, message)
        if (destroyed) return
        main.post {
            if (destroyed) return@post
            buffer.append(TIME_FORMAT.format(Date())).append(' ').append(message).append('\n')
            logView.text = buffer.toString()
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private companion object {
        const val EXTRA_AUTO = "auto"

        /** 免费、匿名可取的歌（`spec/fixtures/entitlement/playback.json` 的 full-exact 用例就是它）。 */
        const val SONG_ID = 5257138L

        /** W0 冒烟只验证 standard 档：匿名账号拿不到更高档，验证也没意义。 */
        const val REQUEST_LEVEL = "standard"

        /** 播放时长。够看到位置推进即可，不必把整首放完。 */
        const val PLAY_SECONDS = 20

        val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
}
