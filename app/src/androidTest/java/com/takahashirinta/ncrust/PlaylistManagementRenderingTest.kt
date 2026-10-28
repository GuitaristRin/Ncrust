package com.takahashirinta.ncrust

import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.takahashirinta.ncrust.network.PlaylistApi.PlaylistInfo
import com.takahashirinta.ncrust.playlist.*
import com.takahashirinta.ncrust.ui.components.PlaylistManagementPanel
import com.takahashirinta.ncrust.ui.components.PlaylistSelectionActions
import com.takahashirinta.ncrust.ui.i18n.*
import com.takahashirinta.ncrust.ui.theme.*
import io.github.takahashirinta.kanesumi.core.theme.MetroTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 真实 Compose 展示层 + 合成状态；所有提交回调留在内存，不连接写接口。 */
@RunWith(AndroidJUnit4::class)
class PlaylistManagementRenderingTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private var renderId = 0
    private var renderedView: android.view.View? = null
    private val info = PlaylistInfo(42, "夜行 · 测试歌单", "", 2, 9, 0, 10)
    private fun seed(mode: PlaylistPanelMode) = PlaylistPanelState(
        visible = true, request = PlaylistPanelRequest(mode, playlistId = if (mode == PlaylistPanelMode.EDIT) 42 else 0),
        ownerId = 9, snapshot = PlaylistSnapshot(info, "夜晚与旅途的音乐", listOf(1, 2), true),
        collection = PlaylistCollection(listOf(info, info.copy(id = 43, name = "通勤", privacy = 0)), true),
        draft = if (mode == PlaylistPanelMode.CREATE) PlaylistDraft("", "", 10) else PlaylistDraft(info.name, "夜晚与旅途的音乐", 10)
    )

    @Test fun visibilityAndDraftAreSubmittedTogetherWithoutNetwork() {
        val state = mutableStateOf(seed(PlaylistPanelMode.EDIT))
        val submitted = AtomicReference<PlaylistDraft>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            render(scenario, state, zhCN, false) { submitted.set(state.value.draft) }
            setText(0, "新的名称")
            awaitUi { state.value.draft.name == "新的名称" }
            setText(1, "新的简介")
            awaitUi { state.value.draft.description == "新的简介" }
            click(zhCN.playlists.public)
            assertEquals(0, state.value.draft.privacy)
            click(zhCN.playlists.save)
            assertEquals(PlaylistDraft("新的名称", "新的简介", 0), submitted.get())
            screenshot("edit-interaction")
        }
    }

    @Test fun editorPickerAndPendingStatesFitBothThemes() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val cases = listOf(
                "create" to seed(PlaylistPanelMode.CREATE),
                "edit" to seed(PlaylistPanelMode.EDIT),
                "picker" to seed(PlaylistPanelMode.ADD),
                "remove" to seed(PlaylistPanelMode.REMOVE).copy(request = PlaylistPanelRequest(PlaylistPanelMode.REMOVE, listOf(1, 2), 42)),
                "delete" to seed(PlaylistPanelMode.DELETE),
                "pending" to seed(PlaylistPanelMode.EDIT).copy(result = PlaylistResult(PlaylistOutcome.PENDING, 42, problem = PlaylistProblem.NETWORK))
            )
            for (dark in listOf(false, true)) for ((name, value) in cases) {
                val state = mutableStateOf(value)
                render(scenario, state, zhCN, dark) {}
                if (name == "pending") {
                    assertTrue(nodes().any { it.text?.toString() == zhCN.playlists.verify })
                    assertFalse(nodes().any { it.text?.toString() == zhCN.playlists.save })
                }
                if (name == "create") {
                    reveal(zhCN.playlists.create, button = true)
                    assertFalse(nodes().first { it.text?.toString() == zhCN.playlists.create && it.className == "android.widget.Button" }.isEnabled)
                }
                screenshot("$name-${if (dark) "dark" else "light"}")
            }
            for (preset in languagePresets) {
                render(scenario, mutableStateOf(seed(PlaylistPanelMode.EDIT)), preset.strings, true) {}
                reveal(preset.strings.playlists.visibility)
                assertTrue(nodes().any { it.text?.toString() == preset.strings.playlists.visibility })
                reveal(preset.strings.playlists.save, button = true)
                if (nodes().none { it.text?.toString() == preset.strings.playlists.save && fullyVisible(it) }) {
                    screenshot("save-diagnostic-${preset.code}")
                    File(instrumentation.targetContext.getExternalFilesDir(null), "playlist-scroll-diagnostic.txt").writeText(
                        "view=${renderedView!!.width}x${renderedView!!.height}\n" + nodes().joinToString("\n") {
                            val rect = android.graphics.Rect().also(it::getBoundsInScreen)
                            "${it.className} text=${it.text} scrollable=${it.isScrollable} bounds=$rect actions=${it.actionList}"
                        })
                }
                assertTrue(nodes().any { it.text?.toString() == preset.strings.playlists.save && fullyVisible(it) })
                screenshot("locale-${preset.code}")
            }
        }
    }

    @Test fun batchActionsRequireSelectionAndRespectOwnership() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val count = mutableStateOf(0)
            var submitted = 0
            render(scenario, mutableStateOf(seed(PlaylistPanelMode.EDIT)), zhCN, true, body = {
                Box(Modifier.statusBarsPadding().padding(20.dp)) {
                    PlaylistSelectionActions(count.value, true, { count.value = 2 }, { count.value = 0 }, { submitted = count.value }, {})
                }
            }) {}
            assertFalse(nodes().first { it.text?.toString() == zhCN.playlists.add }.isEnabled)
            click(zhCN.playlists.selectAll)
            awaitUi { nodes().any { it.text?.toString() == zhCN.playlists.selectionCount(2) } }
            click(zhCN.playlists.add)
            assertEquals(2, submitted)
            screenshot("batch-selection")
            render(scenario, mutableStateOf(seed(PlaylistPanelMode.EDIT)), zhCN, true, body = {
                PlaylistSelectionActions(2, false, {}, {}, {}, {})
            }) {}
            assertFalse(nodes().any { it.text?.toString() == zhCN.playlists.remove })
        }
    }

    private fun render(scenario: ActivityScenario<MainActivity>, state: MutableState<PlaylistPanelState>, strings: Strings, dark: Boolean,
        body: (@Composable () -> Unit)? = null, submit: () -> Unit) {
        val frame = CountDownLatch(1)
        val marker = "playlist-render-${++renderId}"
        scenario.onActivity { activity ->
            renderedView = activity.window.decorView
            activity.setContent {
                key(state, strings, dark) {
                    CompositionLocalProvider(LocalStrings provides strings) {
                        NcrustTheme(Color(0xFF1DB954), dark) {
                            MetroTheme(LocalNcrustColors.current.toMetroColors(dark)) {
                                Box(Modifier.fillMaxSize().semantics { contentDescription = marker }.onGloballyPositioned {
                                    val view = activity.window.decorView
                                    if (android.os.Build.VERSION.SDK_INT >= 29) view.viewTreeObserver.registerFrameCommitCallback { frame.countDown() }
                                    else view.postOnAnimation { frame.countDown() }
                                }) {
                                    if (body != null) body() else PlaylistManagementPanel(state.value,
                                        { change -> state.value = state.value.copy(draft = change(state.value.draft)) },
                                        { submit() }, {}, {}, {}, {}, {}, {}, {})
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue("Frame must render", frame.await(10, TimeUnit.SECONDS))
        awaitUi { nodes().any { it.contentDescription?.toString() == marker } }
        if (body == null) {
            val title = when (state.value.request!!.mode) {
                PlaylistPanelMode.ADD -> strings.playlists.add
                PlaylistPanelMode.CREATE -> strings.playlists.create
                PlaylistPanelMode.EDIT -> strings.playlists.edit
                PlaylistPanelMode.REMOVE -> strings.playlists.remove
                PlaylistPanelMode.DELETE -> strings.playlists.delete
            }
            awaitUi { nodes().any { it.text?.toString() == title } }
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        fun walk(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
            // Compose 滚动后虚拟节点 ID 可不变，必须刷新缓存中的边界再决定是否继续滚动。
            node.refresh()
            return listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::walk).orEmpty() }
        }
        return walk(automation.rootInActiveWindow)
    }
    private fun idle() { instrumentation.waitForIdleSync(); automation.waitForIdle(300, 8_000) }
    private fun awaitUi(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 8_000
        do {
            idle()
            if (condition()) return
        } while (android.os.SystemClock.uptimeMillis() < deadline)
        assertTrue("UI did not reach expected state", condition())
    }
    private fun reveal(text: String, button: Boolean = false) {
        repeat(5) {
            if (nodes().any { it.text?.toString() == text && fullyVisible(it) && (!button || it.className == "android.widget.Button") }) return
            val scroller = nodes().firstOrNull { it.isScrollable } ?: return
            scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            idle()
        }
    }
    private fun fullyVisible(node: AccessibilityNodeInfo): Boolean {
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        val view = renderedView!!
        val bottom = if (android.os.Build.VERSION.SDK_INT >= 30)
            view.rootWindowInsets.getInsets(android.view.WindowInsets.Type.systemBars()).bottom
            else @Suppress("DEPRECATION") view.rootWindowInsets.stableInsetBottom
        return node.isVisibleToUser && bounds.top >= 0 && bounds.bottom <= view.height - bottom
    }
    private fun click(text: String) {
        reveal(text, button = true)
        var node = nodes().first { it.text?.toString() == text && it.isVisibleToUser }
        while (!node.isClickable) node = node.parent ?: error("No clickable parent for $text")
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        idle()
    }
    private fun setText(index: Int, text: String) {
        val node = nodes().filter { it.isEditable }[index]
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
        idle()
    }
    private fun screenshot(name: String) {
        // 语义树可以先于 GPU 提交更新；截图必须等到该树之后的一帧提交，不能拍上一页。
        val committed = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val view = renderedView!!
            view.postOnAnimation {
                if (android.os.Build.VERSION.SDK_INT >= 29) view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                else view.postOnAnimation { committed.countDown() }
                view.invalidate()
            }
        }
        assertTrue("Screenshot frame must commit", committed.await(10, TimeUnit.SECONDS))
        val bitmap = automation.takeScreenshot()
        assertNotNull(bitmap)
        val layout = InstrumentationRegistry.getArguments().getString("layout", "phone")
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "playlist-$layout-$name.png")
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
