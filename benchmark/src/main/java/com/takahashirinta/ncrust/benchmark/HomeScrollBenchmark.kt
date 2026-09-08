package com.takahashirinta.ncrust.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE = "com.takahashirinta.ncrust"

/**
 * 首页滚动帧率基线：冷启进首页后上下滚动主列表,统计每帧耗时分布。
 * 指标：FrameTimingMetric(50/90/95/99 分位帧耗时 + jank 帧数)。
 * 滚动目标是"最高的纵向可滚动容器"——首页主 LazyColumn(每日推荐的横滑 LazyRow 会被过滤掉)。
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

        // 首页内容由 AppWarmup 在冷启期间预取,等待可滚动容器出现即可。
        val verticalList = device.wait(
            Until.findObject(By.scrollable(true)),
            5_000,
        ).let { first ->
            if (first.visibleBounds.height() > first.visibleBounds.width()) {
                first
            } else {
                // 第一个匹配到的是横滑的每日推荐 LazyRow,再找纵向主列表
                device.findObjects(By.scrollable(true))
                    .firstOrNull { it.visibleBounds.height() > it.visibleBounds.width() }
                    ?: first
            }
        }

        repeat(6) { verticalList.swipe(Direction.UP, 1.0f) }
        device.waitForIdle()
        repeat(3) { verticalList.swipe(Direction.DOWN, 1.0f) }
        device.waitForIdle()
    }
}
