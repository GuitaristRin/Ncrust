package com.takahashirinta.ncrust

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.playlist.*
import com.takahashirinta.ncrust.ui.components.*
import com.takahashirinta.ncrust.ui.i18n.*
import com.takahashirinta.ncrust.ui.screen.PlaylistTrackRow
import com.takahashirinta.ncrust.ui.theme.*
import io.github.takahashirinta.kanesumi.anim.sokuou.SokuouTweens
import io.github.takahashirinta.kanesumi.core.theme.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 用生产的页头、工具条、[PlaylistTrackRow] 与 [ReorderState] 拼出歌单编辑态，合成数据、无网络。
 * 覆盖：进出编辑态不移动行布局、点按选择、手柄拖拽真实改变顺序并切换为「保存顺序」，
 * 以及选择器底部面板与轻提示条的实际呈现。
 */
@RunWith(AndroidJUnit4::class)
class PlaylistEditModeRenderingTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private var view: android.view.View? = null
    private var renderId = 0
    private val songs = (1..14).map { SongItem(it.toLong(), "曲目 $it", null, null, 180_000L + it * 7_000L) }

    @Test fun editModeKeepsRowsInPlaceAndDragReorders() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val order = AtomicReference(songs.map { it.id })
            render(scenario, dark = false) { Harness(owned = true, onOrder = order::set) }
            val before = bounds("曲目 2")
            screenshot("normal")
            click(zhCN.playlists.editTracks)
            awaitUi { nodes().any { it.text?.toString() == zhCN.playlists.selectTracks } }
            awaitAnimation()
            assertEquals("进入编辑态不应移动行", before, bounds("曲目 2"))
            click("曲目 2")
            awaitUi { nodes().any { it.text?.toString() == zhCN.playlists.selectionCount(1) } }
            screenshot("edit-selected")

            // 选一行，使手指向下移两行半后仍离屏幕底边一行半以上（小屏先把列表上滚），
            // 避免落进自动滚动区或屏幕外；按住不放时截一张拖动中的画面。
            val rowHeight = bounds("曲目 3").top - bounds("曲目 2").top
            val limit = view!!.height - rowHeight * 4
            fun handles() = nodes().filter { it.contentDescription?.toString() == zhCN.playlists.dragToReorder && it.isVisibleToUser }
                // 跳过被顶部工具条遮住的行（状态栏 + 56dp 工具条 < 两行高）。
                .map { node -> Rect().also(node::getBoundsInScreen) }.filter { it.top > rowHeight * 2 }.sortedBy { it.top }
            if (handles().none { it.centerY() < limit }) {
                nodes().first { it.isScrollable }.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                awaitAnimation()
            }
            val handle = handles().first { it.centerY() < limit }
            val title = nodes().first { node -> node.text?.toString()?.startsWith("曲目 ") == true &&
                Rect().also(node::getBoundsInScreen).let { it.top < handle.centerY() && it.bottom > handle.top } }
            val draggedId = title.text.toString().removePrefix("曲目 ").toLong()
            val fromIndex = order.get().indexOf(draggedId)
            val x = handle.centerX(); var y = handle.centerY()
            shell("input motionevent DOWN $x $y")
            // 2.25 行：中心点稳稳落在下方第二行内，不压在行边界上。
            repeat(9) { y += rowHeight / 4; shell("input motionevent MOVE $x $y") }
            idle()
            screenshot("dragging")
            shell("input motionevent UP $x $y")
            awaitUi { order.get().indexOf(draggedId) != fromIndex }
            val moved = order.get()
            assertEquals("被拖行应下移两位", fromIndex + 2, moved.indexOf(draggedId))
            assertEquals(songs.size, moved.toSet().size)
            awaitUi { nodes().any { it.text?.toString() == zhCN.playlists.saveOrder } }
            awaitAnimation()
            screenshot("order-changed")
        }
    }

    @Test fun pickerSheetAndNoticeRender() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val info = PlaylistInfo(42, "夜行 · 测试歌单", "", 12, 9, 0, 10)
            val state = PlaylistPanelState(visible = true, request = PlaylistPanelRequest(PlaylistPanelMode.ADD, listOf(1, 2, 3)),
                ownerId = 9, collection = PlaylistCollection(listOf(info, info.copy(id = 43, name = "通勤", privacy = 0)), true))
            var picked = 0L
            for (dark in listOf(false, true)) {
                render(scenario, dark) {
                    Box(Modifier.fillMaxSize()) {
                        Harness(owned = true, onOrder = {})
                        PlaylistPanelHost(state, PlaylistManagementActions({}, { picked = it }, {}, {}, {}, {}, {}, {}, {}, {}))
                    }
                }
                awaitUi { nodes().any { it.text?.toString() == "通勤" } }
                awaitAnimation()
                screenshot("picker-sheet-${if (dark) "dark" else "light"}")
            }
            click("通勤")
            assertEquals(43L, picked)
            render(scenario, dark = false) {
                Box(Modifier.fillMaxSize()) {
                    Harness(owned = true, onOrder = {})
                    Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)) {
                        PlaylistNoticeBar(zhCN.playlists.working, busy = true, progress = { 1f }, actionLabel = null, onAction = {}, onDismiss = {})
                        Spacer(Modifier.height(8.dp))
                        PlaylistNoticeBar(zhCN.playlists.noticeCreated("夜行 · 测试歌单"), busy = false, progress = { 1f },
                            actionLabel = zhCN.playlists.view, onAction = {}, onDismiss = {})
                    }
                }
            }
            awaitUi { nodes().any { it.text?.toString() == zhCN.playlists.view } }
            screenshot("notice")
            var confirmed = false
            // 对话框是独立窗口、成为 active window，标记节点在下层窗口里，不能再等它。
            render(scenario, dark = true, awaitMarker = false) {
                Box(Modifier.fillMaxSize()) {
                    Harness(owned = true, onOrder = {})
                    PlaylistConfirmDialog(zhCN.playlists.removeHint(3), zhCN.playlists.removeShort, { confirmed = true }, {})
                }
            }
            awaitUi { nodes().any { it.text?.toString() == zhCN.playlists.removeShort } }
            awaitAnimation()
            screenshot("confirm-dialog")
            click(zhCN.playlists.removeShort)
            assertTrue(confirmed)
        }
    }

    /** 与 PlaylistDetailScreen 相同的状态组合，去掉网络读取。 */
    @Composable
    private fun Harness(owned: Boolean, onOrder: (List<Long>) -> Unit) {
        var editing by remember { mutableStateOf(false) }
        var selected by remember { mutableStateOf(emptySet<Long>()) }
        var order by remember { mutableStateOf(songs.map { it.id }) }
        val progress = remember { Animatable(0f) }
        LaunchedEffect(editing) { progress.animateTo(if (editing) 1f else 0f, if (editing) SokuouTweens.SheetAppear else SokuouTweens.SheetDismiss) }
        val chrome by remember { derivedStateOf { progress.value > 0f } }
        val listState = rememberLazyListState()
        val reorder = rememberReorderState(listState, canMove = { it is Long }) { from, to ->
            val f = order.indexOf(from as Long); val t = order.indexOf(to as Long)
            if (f >= 0 && t >= 0) { order = order.toMutableList().apply { add(t, removeAt(f)) }; onOrder(order) }
        }
        val byId = songs.associateBy { it.id }
        val changed = order != songs.map { it.id }
        DetailScaffold(
            title = "", onBack = {}, listState = listState,
            topBar = if (chrome) { {
                PlaylistEditBar(progress = { progress.value }, selectionCount = selected.size, totalCount = songs.size,
                    owned = owned, orderChanged = changed, busy = false,
                    onExit = { editing = false }, onToggleAll = {}, onRemove = {}, onAddTo = {}, onSaveOrder = {})
            } } else null,
            header = {
                PlaylistDetailHeader(null, "夜行与城市", zhCN.playlists.private, songs.size, "在城市安静下来之后，留给归途的音乐。",
                    true, true, owned, {}, { editing = true }, {}, {}, dimmed = { progress.value })
            },
            content = {
                itemsIndexed(order.mapNotNull { byId[it] }, key = { _, song -> song.id }) { _, song ->
                    PlaylistTrackRow(
                        song = song, editProgress = { progress.value }, editing = editing, selected = song.id in selected,
                        pendingRemoval = false, reorder = reorder, showHandle = owned && chrome,
                        revealed = false, swipeEnabled = false, onRevealChange = {}, onSwipeRemove = {},
                        onClick = { if (editing) selected = if (song.id in selected) selected - song.id else selected + song.id },
                        onShowMenu = null, moveActions = emptyList()
                    )
                }
            }
        )
    }

    private fun render(scenario: ActivityScenario<MainActivity>, dark: Boolean, awaitMarker: Boolean = true, content: @Composable () -> Unit) {
        val ready = CountDownLatch(1)
        val marker = "edit-mode-${++renderId}"
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
        if (awaitMarker) awaitUi { nodes().any { it.contentDescription?.toString() == marker } }
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
    /** 等待进出场动画（SheetAppear 300ms）走完：以帧回调计时，不用 sleep 当同步。 */
    private fun awaitAnimation() {
        val done = CountDownLatch(1)
        val start = android.os.SystemClock.uptimeMillis()
        fun tick() { view!!.postOnAnimation { if (android.os.SystemClock.uptimeMillis() - start > 450) done.countDown() else tick() } }
        instrumentation.runOnMainSync { tick() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        idle()
    }
    private fun bounds(text: String): Rect = Rect().also(nodes().first { it.text?.toString() == text }::getBoundsInScreen)
    private fun click(text: String) {
        var node = nodes().first { it.text?.toString() == text && it.isVisibleToUser }
        while (!node.isClickable) node = node.parent ?: error("No click target for $text")
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        idle()
    }
    private fun shell(command: String) {
        // 读完输出才返回：保证注入的触摸事件已经执行。
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
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
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "editmode-$layout-$name.png")
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
