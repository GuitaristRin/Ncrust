package com.takahashirinta.ncrust.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE = "com.takahashirinta.ncrust"

/**
 * 冷启动基线：从进程冷起(COLD)到首帧的时间。
 * 指标：StartupTimingMetric(首帧时间)。
 *
 * 前置条件：被测应用已安装并**保持登录态**(见 benchmark/run_benchmark.sh 的生产式
 * 流程——不重装、不卸载)。未登录时首页无数据, 启动路径失真。
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStart() = benchmarkRule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = 8,
        startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.DEFAULT,
    ) {
        startActivityAndWait()
    }
}