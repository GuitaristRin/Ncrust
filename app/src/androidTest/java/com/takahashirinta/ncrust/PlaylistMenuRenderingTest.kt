package com.takahashirinta.ncrust

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.takahashirinta.ncrust.ui.components.*
import com.takahashirinta.ncrust.ui.i18n.*
import com.takahashirinta.ncrust.ui.screen.PlaylistSearchRow
import com.takahashirinta.ncrust.ui.screen.fittingTabStyle
import com.takahashirinta.ncrust.ui.theme.*
import io.github.takahashirinta.kanesumi.controls.MetroTabItem
import io.github.takahashirinta.kanesumi.controls.MetroTabRow
import io.github.takahashirinta.kanesumi.core.theme.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 播放列表长按操作表、详情页收藏按钮与搜索「播放列表」分类的实际呈现。合成数据、无网络、不写账号。
 */
@RunWith(AndroidJUnit4::class)
class PlaylistMenuRenderingTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private var view: android.view.View? = null
    private var renderId = 0
    private val target = PlaylistMenuTarget(42, "夜行 · 城市漫游与深夜电台精选", "", 128, "某位创建者")

    @Test fun playlistSheetShowsActionsAndDispatches() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val p = zhCN.playlists
            var picked: String? = null
            var dismissed = 0
            for (dark in listOf(false, true)) {
                render(scenario, dark) {
                    val actions = listOf(
                        SongMenuAction(Icons.Default.PlayArrow, zhCN.playAllButton) { picked = "play" },
                        SongMenuAction(Icons.AutoMirrored.Filled.PlaylistPlay, zhCN.actionInsertNext) { picked = "next" },
                        SongMenuAction(Icons.AutoMirrored.Filled.QueueMusic, zhCN.actionAppendToQueue) { picked = "append" },
                        SongMenuAction(Icons.Default.Edit, p.editInfo) { picked = "edit" },
                        SongMenuAction(Icons.Default.Checklist, p.selectMultiple) { picked = "select" },
                        SongMenuAction(Icons.Default.DeleteOutline, p.delete) { picked = "delete" },
                    )
                    PlaylistMenuSheet(target, actions, onDismiss = { dismissed++ })
                }
                awaitUi { nodes().any { it.text?.toString() == p.selectMultiple && it.isVisibleToUser } }
                awaitAnimation()
                screenshot("playlist-sheet-${if (dark) "dark" else "light"}")
            }
            // 抬头携带名称、创建者与曲目数，操作项按传入顺序排列。
            val labels = listOf(zhCN.playAllButton, zhCN.actionInsertNext, zhCN.actionAppendToQueue, p.editInfo, p.selectMultiple, p.delete)
            val tops = labels.map { label -> android.graphics.Rect().also(nodes().first { it.text?.toString() == label }::getBoundsInScreen).top }
            assertEquals(tops.sorted(), tops)
            assertTrue(nodes().any { it.text?.toString() == "某位创建者" })
            assertTrue(nodes().any { it.text?.toString() == zhCN.trackCount(128) })
            click(p.selectMultiple)
            assertEquals("select", picked)
            assertEquals(1, dismissed)
        }
    }

    @Test fun detailHeaderSubscribeToggles() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val p = zhCN.playlists
            var subscribed by mutableStateOf(false)
            render(scenario, dark = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PlaylistDetailHeader(
                        coverUrl = null, title = target.name, privacy = p.public, trackCount = 128,
                        description = "合成数据，仅用于渲染。", complete = true, management = true, owned = false,
                        onPlay = {}, onEditTracks = {}, onEdit = {}, onDelete = {},
                        subscribed = subscribed, onToggleSubscribe = { subscribed = !subscribed }
                    )
                }
            }
            awaitUi { nodes().any { it.text?.toString() == p.subscribe } }
            screenshot("detail-subscribe")
            // 他人歌单：没有「编辑信息」与管理菜单，只有收藏与选择歌曲。
            assertFalse(nodes().any { it.text?.toString() == p.editInfo })
            assertTrue(nodes().any { it.text?.toString() == p.selectTracks })
            click(p.subscribe)
            awaitUi { nodes().any { it.text?.toString() == p.subscribedLabel } }
            screenshot("detail-subscribed")
        }
    }

    @Test fun searchPlaylistTabFitsEveryLocale() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val locales = listOf(zhCN, zhTW, en, jpJP, jpMY, koNK, deDE, ruRU)
            val wrapped = mutableListOf<String>()
            render(scenario, dark = true) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val measurer = rememberTextMeasurer()
                    val tabWidth = with(LocalDensity.current) { (maxWidth / 4).roundToPx() }
                    // MetroTabRow 的标签不限行数：放不下会折成两行并被 48dp 行高裁掉。按生产代码选出的字号、
                    // 以选中态（Medium）逐个量，任何一个折行都算失败；字号也不得小于 12sp。
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        locales.forEach { s ->
                            val titles = listOf(s.searchCategoryTracks, s.searchCategoryAlbums, s.searchCategoryArtists, s.searchCategoryPlaylists)
                            val style = fittingTabStyle(measurer, titles, tabWidth)
                            if (style.fontSize.value < 12f) wrapped += "font ${style.fontSize}"
                            titles.filter { measurer.measure(it, style.copy(fontWeight = FontWeight.Medium),
                                constraints = Constraints(maxWidth = tabWidth)).lineCount > 1 }
                                .forEach { if (it !in wrapped) wrapped += it }
                            MetroTabRow(titles.map { MetroTabItem(it) }, selectedTabIndex = 3, onTabSelected = {}, textStyle = style)
                        }
                        CompositionLocalProvider(LocalStrings provides zhCN) {
                            PlaylistSearchRow(target, onClick = {}, onLongClick = {})
                            PlaylistSearchRow(target.copy(id = 43, name = "Короткий", creator = null, trackCount = -1), onClick = {}, onLongClick = {})
                        }
                    }
                }
            }
            awaitUi { nodes().any { it.text?.toString() == ruRU.searchCategoryPlaylists } }
            screenshot("search-tabs")
            assertTrue("labels wrap: $wrapped", wrapped.isEmpty())
        }
    }

    private fun render(scenario: ActivityScenario<MainActivity>, dark: Boolean, content: @Composable () -> Unit) {
        val ready = CountDownLatch(1)
        val marker = "playlist-menu-${++renderId}"
        scenario.onActivity { activity ->
            view = activity.window.decorView
            activity.setContent {
                key(marker) {
                    CompositionLocalProvider(LocalStrings provides zhCN) {
                        NcrustTheme(Color(0xFF1DB954), dark) {
                            MetroTheme(LocalNcrustColors.current.toMetroColors(dark)) {
                                Box(Modifier.fillMaxSize().background(LocalMetroColors.current.background)
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
    /** 等待操作表入场动画走完：以帧回调计时，不用 sleep 当同步。 */
    private fun awaitAnimation() {
        val done = CountDownLatch(1)
        val start = android.os.SystemClock.uptimeMillis()
        fun tick() { view!!.postOnAnimation { if (android.os.SystemClock.uptimeMillis() - start > 450) done.countDown() else tick() } }
        instrumentation.runOnMainSync { tick() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        idle()
    }
    private fun click(text: String) {
        var node = nodes().first { it.text?.toString() == text && it.isVisibleToUser }
        while (!node.isClickable) node = node.parent ?: error("No click target for $text")
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        idle()
    }
    private fun screenshot(name: String) {
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
        val layout = InstrumentationRegistry.getArguments().getString("layout", "phone")
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "menu-$layout-$name.png")
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
