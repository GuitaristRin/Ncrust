package com.takahashirinta.ncrust.player

import android.app.Application
import android.content.SharedPreferences
import com.takahashirinta.ncrust.QueueModes
import com.takahashirinta.ncrust.ui.viewmodel.PlayerViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/** 检查真实 Service → ViewModel 接线，而非只验证 advanceIndex 的模式分支。 */
class PlaybackNavigationCallbackTest {
    private val preferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java)
    ) { _, method, args ->
        when (method.name) {
            "getBoolean", "getString" -> args!![1]
            else -> error("Unexpected preferences access: ${method.name}")
        }
    } as SharedPreferences

    private fun player() = PlayerViewModel(object : Application() {
        override fun getSharedPreferences(name: String, mode: Int) = preferences
    })

    @After
    fun clearServiceCallbacks() {
        PlaybackService.onProgressUpdate = null
        PlaybackService.onPlaybackEnded = null
        PlaybackService.onPlaybackNext = null
        PlaybackService.onPlaybackPrevious = null
        PlaybackService.onIsPlayingChanged = null
        PlaybackService.onSongTransitioned = null
        PlaybackService.onBufferingChanged = null
        PlaybackService.onPlaybackError = null
    }

    @Test
    fun `system next advances single repeat immediately without ending the song`() {
        val player = player()
        var currentIndex = 0
        var ended = 0
        player.setOnSongNextCallback {
            currentIndex = PlaybackQueueLogic.advanceIndex(2, currentIndex, QueueModes.SINGLE, natural = false)
        }
        player.setOnSongEndedCallback { ended++ }

        PlaybackService.onPlaybackNext!!.invoke()

        // 没有运行 Compose、没有等重组或帧时钟，手动下一首已经生效。
        assertEquals(1, currentIndex)
        assertEquals("手动跳过不能进入自然结束及补报整首播放的分支", 0, ended)

        PlaybackService.onPlaybackNext!!.invoke()
        assertEquals("连续按键不能合并成一次 songEnded 状态变化", 0, currentIndex)
        assertEquals(0, ended)
    }

    @Test
    fun `natural end repeats the single song and never dispatches manual next`() {
        val player = player()
        var currentIndex = 0
        var manualNext = 0
        var ended = 0
        player.setOnSongNextCallback { manualNext++ }
        player.setOnSongEndedCallback {
            ended++
            currentIndex = PlaybackQueueLogic.advanceIndex(2, currentIndex, QueueModes.SINGLE, natural = true)
        }

        PlaybackService.onPlaybackEnded!!.invoke()

        assertEquals(0, currentIndex)
        assertEquals(0, manualNext)
        assertEquals(1, ended)
    }
}
