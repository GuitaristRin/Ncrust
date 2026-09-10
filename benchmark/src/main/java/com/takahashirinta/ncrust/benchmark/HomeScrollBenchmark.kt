package com.takahashirinta.ncrust.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import android.os.SystemClock
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE = "com.takahashirinta.ncrust"

/**
 * 首页滚动帧率基线：冷启进首页后上下滚动主列表,统计每帧耗时分布。
 * 指标：FrameTimingMetric(50/90/95/99 分位帧耗时 + jank 帧数)。
 *
 * 前置条件：被测应用已安装并**保持登录态**(见 benchmark/run_benchmark.sh 的生产式
 * 流程——不重装、不卸载)。Ncrust 默认落在「库」tab——必须切到首页再抓纵向主列表。
 */
@RunWith(AndroidJUnit4::class)
class HomeScrollBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun homeScroll() = benchmarkRule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = 6,
        startupMode = StartupMode.COLD,
    ) {
        startActivityAndWait()
        device.waitForIdle()

        fun swipeList(direction: Direction) {
            // 每次滑动前重新查找纵向容器: LazyColumn 节点滚动中会回收重建,
            // 偶发失效重试一次, 避免整轮迭代因 a11y 树抖动失败
            repeat(2) { attempt ->
                try {
                    findHomeVerticalList(device, timeoutMs = 5_000).swipe(direction, 1.0f)
                    return
                } catch (stale: StaleObjectException) {
                    if (attempt == 1) throw AssertionError("列表节点持续失效，滚动失败", stale)
                }
            }
        }

        repeat(6) { swipeList(Direction.UP); device.waitForIdle() }
        repeat(3) { swipeList(Direction.DOWN); device.waitForIdle() }
    }

    /** 切到首页 tab 并轮询直至出现纵向主列表。 */
    private fun findHomeVerticalList(
        device: androidx.test.uiautomator.UiDevice,
        timeoutMs: Long
    ): UiObject2 {
        val homeTab = device.wait(Until.findObject(By.text("首页")), timeoutMs)
            ?: device.wait(Until.findObject(By.textContains("Home")), 3_000)
            ?: error("未见底部导航(可能 splash 未结束或登录页拦截)")
        homeTab.click()

        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val vertical = try {
                device.findObjects(By.scrollable(true))
                    .firstOrNull { it.visibleBounds.height() > it.visibleBounds.width() }
            } catch (_: StaleObjectException) {
                null
            }
            if (vertical != null) return vertical
            SystemClock.sleep(250)
        }
        error("${timeoutMs}ms 内未找到首页纵向列表(登录态无效或首页数据为空?)")
    }
}