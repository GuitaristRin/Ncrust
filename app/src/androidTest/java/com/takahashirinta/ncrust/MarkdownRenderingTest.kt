package com.takahashirinta.ncrust

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.key
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.takahashirinta.ncrust.ui.theme.MarkdownText
import com.takahashirinta.ncrust.update.ApkArchive
import io.github.takahashirinta.kanesumi.core.theme.MetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 在模拟器运行；截图保存在被测应用 externalFilesDir，方便 adb pull 后人工验收。 */
@RunWith(AndroidJUnit4::class)
class MarkdownRenderingTest {
    @Test fun packageManagerReadsUpdateArchive() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = InstrumentationRegistry.getArguments().getString("updateApk")
            ?: context.applicationInfo.sourceDir
        val identity = ApkArchive.readArchive(context, File(path))
        assertNotNull("PackageManager 必须能读出 APK 身份", identity)
        assertEquals(context.packageName, identity!!.packageName)
        assertTrue(identity.versionCode > 0)
        assertTrue(identity.signatureDigests.isNotEmpty())
    }

    @Test fun releaseTableRendersAndScrollsInBothThemes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (light in listOf(false, true)) {
                val rendered = CountDownLatch(1)
                val colors = if (light) MetroColors(
                    background = Color(0xFFF6F2E9), onBackground = Color(0xFF171717),
                    surfaceVariant = Color(0xFFE5E0D8), primary = Color(0xFF3B82F6)
                ) else MetroColors(primary = Color(0xFF3B82F6))
                scenario.onActivity { activity ->
                    activity.setContent {
                        key(light) {
                        MetroTheme(colors = colors) {
                            Column(Modifier.fillMaxSize().background(colors.background)
                                .onGloballyPositioned {
                                    val view = activity.window.decorView
                                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                                        view.viewTreeObserver.registerFrameCommitCallback { rendered.countDown() }
                                    } else view.postOnAnimation { view.postOnAnimation { rendered.countDown() } }
                                }
                                .statusBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
                                MarkdownText(NOTES)
                            }
                        }
                        }
                    }
                }
                assertTrue("新主题必须完成一帧渲染", rendered.await(10, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                automation.waitForIdle(500, 10_000)
                screenshot(if (light) "light" else "dark")
                assertTrue(findText(automation.rootInActiveWindow, "平台").isNotEmpty())
                val root = automation.rootInActiveWindow
                assertTrue("宽表必须支持横向滚动", scroll(root))
                instrumentation.waitForIdleSync()
                automation.waitForIdle(500, 10_000)
                screenshot(if (light) "light-scrolled" else "dark-scrolled")
                assertTrue(findText(automation.rootInActiveWindow, "APK").any { it.isVisibleToUser })
            }
        }
    }

    // Compose 的虚拟节点不实现 provider 的按文本查询；遍历同一语义树，保留原验收要求。
    private fun findText(node: AccessibilityNodeInfo, text: String): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        if (node.text?.contains(text) == true) found += node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { found += findText(it, text) }
        }
        return found
    }

    private fun scroll(node: AccessibilityNodeInfo): Boolean {
        if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            if (scroll(child)) return true
        }
        return false
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull(bitmap)
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "markdown-$name.png")
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    companion object {
        private val NOTES = """
            # Android 1.4.1

            ## 更新内容

            | 平台 | 版本 | 下载 |
            |---|:---:|---|
            | Android（通用包） | **1.4.1** | [`APK`](https://example.org/ncrust.apk) |
            | Windows（UWP，x64） | 1.0.3 | [`.appinstaller`](https://example.org/ncrust.appinstaller) |

            - 收藏状态以全量红心 ID 为准。
            - 表格支持横向滚动与可点击的下载链接。

            ```text
            a | b
            --- | ---
            ```
        """.trimIndent()
    }
}
