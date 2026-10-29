package com.takahashirinta.ncrust

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.takahashirinta.ncrust.network.PlaylistApi.PlaylistInfo
import com.takahashirinta.ncrust.playlist.*
import com.takahashirinta.ncrust.ui.components.*
import com.takahashirinta.ncrust.ui.i18n.*
import com.takahashirinta.ncrust.ui.theme.*
import io.github.takahashirinta.kanesumi.core.theme.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 合成账号状态驱动生产组件；所有回调留在内存。 */
@RunWith(AndroidJUnit4::class)
class PlaylistCollectionRenderingTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private var view: android.view.View? = null
    private var renderId = 0
    private var currentDark = false
    private val longTitle = "夜行与城市的另一面 · 在回家路上听完的播放列表"
    private val items = listOf(
        PlaylistInfo(42, longTitle, "", 128, 9, 0, 10),
        PlaylistInfo(43, "周末的唱片", "", 24, 9, 0, 0),
        PlaylistInfo(44, "海边的旧时光", "", 36, 8, 0, 0, subscribed = true)
    )

    @Test fun collectionUsesReadableRowsAndIndependentOpenPlayCreateActions() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var opened = 0L; var played = 0L; var created = false
            render(scenario, false) {
                PlaylistCollectionContent(PlaylistCollection(items, true), 9, false, null, true,
                    { created = true }, {}, { opened = it.id }, { played = it })
            }
            click(longTitle)
            assertEquals(42L, opened)
            val play = nodes().first { it.contentDescription?.toString() == zhCN.playAllButton }
            activate(play)
            assertEquals(42L, played)
            assertEquals(42L, opened)
            click(zhCN.playlists.create)
            assertTrue(created)
            val bounds = android.graphics.Rect().also(nodes().first { it.text?.toString() == longTitle }::getBoundsInScreen)
            assertTrue(bounds.left >= 72 && bounds.right <= view!!.width)
            screenshot("collection-light")
            render(scenario, true) {
                PlaylistCollectionContent(PlaylistCollection(items, true), 9, false, null, true, {}, {}, {}, {})
            }
            screenshot("collection-dark")
        }
    }

    @Test fun cachedCollectionSurvivesRefreshErrorsAndEmptyCollectionsKeepCreateEntry() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val error = mutableStateOf<String?>(null)
            val data = mutableStateOf(PlaylistCollection(items, true))
            render(scenario, false) {
                PlaylistCollectionContent(data.value, 9, true, error.value, true, {}, {}, {}, {})
            }
            awaitUi { nodes().any { it.text?.toString() == longTitle } }
            error.value = zhCN.playlists.network
            awaitUi { nodes().any { it.text?.toString() == zhCN.playlists.network } }
            assertTrue(nodes().any { it.text?.toString() == longTitle })
            screenshot("collection-error-cached")
            data.value = PlaylistCollection(emptyList(), true)
            render(scenario, true) {
                PlaylistCollectionContent(data.value, 9, false, null, true, {}, {}, {}, {})
            }
            assertTrue(nodes().any { it.text?.toString() == zhCN.playlists.create && it.isEnabled })
            assertTrue(nodes().any { it.text?.toString() == zhCN.noPlaylists })
            screenshot("collection-empty")
        }
    }

    @Test fun detailSeparatesManagementFromPlaybackAndProtectsOtherOwners() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var deleteRequests = 0
            render(scenario, false) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    PlaylistDetailHeader(null, "夜行与城市", zhCN.playlists.private, 128,
                        "在城市安静下来之后，留给归途的音乐。", true, true, true, false,
                        {}, {}, {}, { deleteRequests++ })
                }
            }
            reveal(zhCN.playlists.manage)
            assertFalse(nodes().any { it.text?.toString() == zhCN.playlists.delete })
            screenshot("detail-light")
            click(zhCN.playlists.manage)
            awaitUi { nodes().any { it.text?.toString() == zhCN.playlists.delete } }
            screenshot("detail-menu")
            click(zhCN.playlists.delete)
            assertEquals(1, deleteRequests)
            render(scenario, true) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    PlaylistDetailHeader(null, "海边的旧时光", zhCN.playlists.public, 36, "", true, true, false, false,
                        {}, {}, { fail("another owner's editor") }, { fail("another owner's delete") })
                }
            }
            reveal(zhCN.playlists.selectTracks)
            assertFalse(nodes().any { it.text?.toString() == zhCN.playlists.manage })
            screenshot("detail-subscribed")
            render(scenario, false) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    PlaylistDetailHeader(null, "尚在读取的列表", zhCN.playlists.private, 128, "", false, true, true, false,
                        null, null, {}, {})
                }
            }
            assertTrue(nodes().any { it.text?.toString() == zhCN.playlists.incomplete })
            assertFalse(nodes().any { it.text?.toString() == zhCN.playlists.selectTracks })
            screenshot("detail-incomplete")
        }
    }

    @Test fun editorKeepsSaveReachableAboveTheKeyboard() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val state = mutableStateOf(PlaylistPanelState(visible = true,
                request = PlaylistPanelRequest(PlaylistPanelMode.EDIT, playlistId = 42),
                ownerId = 9, draft = PlaylistDraft("归途", "夜晚的音乐", 10)))
            render(scenario, false) {
                PlaylistManagementPanel(state.value,
                    { change -> state.value = state.value.copy(draft = change(state.value.draft)) },
                    {}, {}, {}, {}, {}, {}, {}, {})
            }
            val field = nodes().first { it.isEditable }
            val target = android.graphics.Rect().also(field::getBoundsInScreen)
            automation.executeShellCommand("input tap ${target.centerX()} ${target.centerY()}").close()
            awaitUi { view!!.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime()) }
            val save = nodes().first { it.text?.toString() == zhCN.playlists.save }
            val bounds = android.graphics.Rect().also(save::getBoundsInScreen)
            val keyboard = view!!.rootWindowInsets.getInsets(android.view.WindowInsets.Type.ime()).bottom
            assertTrue(save.isEnabled && save.isVisibleToUser)
            assertTrue("save must remain above IME", bounds.bottom <= view!!.height - keyboard)
            assertTrue(bounds.top >= 0 && bounds.right <= view!!.width)
            screenshot("editor-keyboard", backgroundY = 80)
            val hidden = CountDownLatch(1)
            instrumentation.runOnMainSync {
                view!!.setWindowInsetsAnimationCallback(object : android.view.WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                    override fun onProgress(insets: android.view.WindowInsets, running: MutableList<android.view.WindowInsetsAnimation>): android.view.WindowInsets = insets
                    override fun onEnd(animation: android.view.WindowInsetsAnimation) {
                        if (animation.typeMask and android.view.WindowInsets.Type.ime() != 0) hidden.countDown()
                    }
                })
            }
            automation.executeShellCommand("input keyevent 4").close()
            assertTrue("IME hide animation", hidden.await(8, TimeUnit.SECONDS))
            awaitUi { !view!!.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime()) }
            screenshot("editor-after-keyboard")
        }
    }

    private fun render(scenario: ActivityScenario<MainActivity>, dark: Boolean, content: @Composable () -> Unit) {
        currentDark = dark
        val ready = CountDownLatch(1)
        val marker = "playlist-layout-${++renderId}"
        scenario.onActivity { activity ->
            view = activity.window.decorView
            activity.setContent {
                key(marker) {
                    CompositionLocalProvider(LocalStrings provides zhCN) {
                        NcrustTheme(Color(0xFF3B82F6), dark) {
                            MetroTheme(LocalNcrustColors.current.toMetroColors(dark)) {
                                Box(Modifier.fillMaxSize().background(LocalMetroColors.current.background).statusBarsPadding().navigationBarsPadding()
                                    .semantics { contentDescription = marker }.onGloballyPositioned {
                                        view!!.viewTreeObserver.registerFrameCommitCallback { ready.countDown() }
                                    }) { content() }
                            }
                        }
                    }
                }
            }
        }
        assertTrue("layout frame", ready.await(10, TimeUnit.SECONDS))
        awaitUi { nodes().any { it.contentDescription?.toString() == marker } }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        fun walk(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
            node.refresh()
            return listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::walk).orEmpty() }
        }
        return automation.rootInActiveWindow?.let(::walk).orEmpty()
    }

    private fun idle() { instrumentation.waitForIdleSync(); automation.waitForIdle(200, 8_000) }
    private fun awaitUi(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 8_000
        do { idle(); if (condition()) return } while (android.os.SystemClock.uptimeMillis() < deadline)
        assertTrue("expected UI state", condition())
    }

    private fun reveal(text: String) {
        repeat(7) {
            if (nodes().any { it.text?.toString() == text && it.isVisibleToUser }) return
            nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            idle()
        }
    }

    private fun activate(node: AccessibilityNodeInfo) {
        var target = node
        while (!target.isClickable) target = target.parent ?: error("No click target")
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        idle()
    }
    private fun click(text: String) { reveal(text); activate(nodes().first { it.text?.toString() == text && it.isVisibleToUser }) }

    private fun screenshot(name: String, backgroundY: Int = view!!.height / 2) {
        val ready = CountDownLatch(1)
        instrumentation.runOnMainSync {
            view!!.postOnAnimation {
                view!!.viewTreeObserver.registerFrameCommitCallback { ready.countDown() }
                view!!.invalidate()
            }
        }
        assertTrue("screenshot frame", ready.await(10, TimeUnit.SECONDS))
        val bitmap = automation.takeScreenshot()
        assertNotNull(bitmap)
        assertEquals(if (currentDark) android.graphics.Color.BLACK else android.graphics.Color.rgb(246, 242, 233),
            bitmap.getPixel(bitmap.width - 1, backgroundY))
        val layout = InstrumentationRegistry.getArguments().getString("layout", "phone")
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "layout-$layout-$name.png")
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
