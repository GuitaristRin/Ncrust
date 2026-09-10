package com.takahashirinta.ncrust.benchmark

import android.os.SystemClock
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE = "com.takahashirinta.ncrust"

/**
 * 播放器「minibar 上拉展开 / 全屏下拉收起」的手感基准。
 *
 * 这是 Ncrust 的核心交互, 卡一下用户体感极差。用真实手势(swipe)驱动 progress,
 * 统计展开/收起动画期间的帧耗时。
 *
 * 前置条件：被测应用已安装并**保持登录态且队列非空**(miniBar 有歌可拉)。
 * 若队列为空, miniBar 显示"暂无播放", 手势仍可拉起(展开态), 但内容较少。
 */
@RunWith(AndroidJUnit4::class)
class ExpandPlayerBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun expandCollapse() = benchmarkRule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = 6,
        startupMode = StartupMode.COLD,
    ) {
        startActivityAndWait()
        device.waitForIdle()
        // 等 splash 淡出 + miniBar 就位
        SystemClock.sleep(1_500)

        val w = device.displayWidth
        val h = device.displayHeight
        val miniBarY = (h * 0.92f).toInt()
        val topY = (h * 0.15f).toInt()

        // 从 miniBar 上滑到全屏（40 步模拟连续拖拽，让 progress 跟手）
        device.swipe(w / 2, miniBarY, w / 2, topY, 40)
        device.waitForIdle()
        SystemClock.sleep(600)

        // 全屏下拉回 miniBar
        device.swipe(w / 2, topY, w / 2, miniBarY, 40)
        device.waitForIdle()
        SystemClock.sleep(400)
    }
}
