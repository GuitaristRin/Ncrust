package com.takahashirinta.ncrust.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.playlist.*
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.i18n.PlaylistStrings
import io.github.takahashirinta.kanesumi.anim.sokuou.SokuouTweens
import io.github.takahashirinta.kanesumi.controls.*
import io.github.takahashirinta.kanesumi.core.theme.*
import kotlinx.coroutines.launch

/** 选择器（尚未出结果的「添加到」）用底部面板；表单、结果与错误用整页。 */
private fun PlaylistPanelState.isPicker() =
    request?.mode == PlaylistPanelMode.ADD && result == null && problem == null

/**
 * 面板宿主：负责进出场与呈现方式，内容交给 [PlaylistManagementPanel]。
 *
 * 旧版 `if (visible) 全屏页`，出现和消失都是硬切；这里用一个进度驱动（graphicsLayer 位移 +
 * 遮罩透明度），并在退场期间保留最后一帧内容。选择器是贴底的面板，点遮罩或下拉标题收起；
 * 选中目标后面板立即收起、写入转入后台，由轻提示报告结果。
 */
@Composable
fun PlaylistManagementOverlay(onDeleted: (Long) -> Unit = {}, onOpen: (Long) -> Unit) {
    val state by PlaylistManager.state.collectAsState()
    PlaylistPanelHost(state, PlaylistManagementActions(
        onDraft = PlaylistManager::updateDraft, onPick = PlaylistManager::submitInBackground, onSubmit = PlaylistManager::submit,
        onCreate = PlaylistManager::createFromPicker, onClose = PlaylistManager::close,
        onFinish = { shown ->
            val deleted = shown.result?.takeIf { shown.request?.mode == PlaylistPanelMode.DELETE && it.outcome == PlaylistOutcome.CONFIRMED }?.playlistId
            PlaylistManager.finish()
            deleted?.let(onDeleted)
        },
        onVerify = PlaylistManager::retryRead, onBackToEdit = PlaylistManager::backToEditor,
        onReload = { request -> PlaylistManager.open(request) }, onOpen = onOpen))
}

/** 面板宿主的全部回调；独立成值便于测试用合成状态驱动真实宿主。 */
class PlaylistManagementActions(
    val onDraft: ((PlaylistDraft) -> PlaylistDraft) -> Unit, val onPick: (Long) -> Unit, val onSubmit: (Long) -> Unit,
    val onCreate: () -> Unit, val onClose: () -> Unit, val onFinish: (PlaylistPanelState) -> Unit,
    val onVerify: () -> Unit, val onBackToEdit: () -> Unit, val onReload: (PlaylistPanelRequest) -> Unit, val onOpen: (Long) -> Unit,
)

@Composable
fun PlaylistPanelHost(state: PlaylistPanelState, actions: PlaylistManagementActions) {
    var retained by remember { mutableStateOf<PlaylistPanelState?>(null) }
    SideEffect { if (state.visible) retained = state }
    val current = if (state.visible) state else retained
    val picker = current?.isPicker() == true
    // 进度按呈现方式分开：选择器 → 新建表单 / 结果页时，新容器从 0 滑入，而不是在原地硬换内容。
    val progress = remember(picker) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.visible, picker) {
        if (state.visible) progress.animateTo(1f, SokuouTweens.SheetAppear)
        else {
            progress.animateTo(0f, SokuouTweens.SheetDismiss)
            retained = null
        }
    }
    val shown = current ?: return
    BackHandler(enabled = state.visible, onBack = actions.onClose)

    val panel = @Composable { modifier: Modifier ->
        PlaylistManagementPanel(shown, actions.onDraft,
            onSubmit = if (shown.isPicker()) actions.onPick else actions.onSubmit,
            onCreate = actions.onCreate, onClose = actions.onClose, onFinish = { actions.onFinish(shown) },
            onVerify = actions.onVerify, onBackToEdit = actions.onBackToEdit,
            onReload = { shown.request?.let(actions.onReload) }, onOpen = actions.onOpen, modifier = modifier)
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()
            .graphicsLayer { alpha = progress.value * if (shown.isPicker()) 0.6f else 0.35f }
            .background(Color.Black)
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = state.visible) { actions.onClose() })
        if (shown.isPicker()) {
            var height by remember { mutableFloatStateOf(0f) }
            val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.72f).dp
            panel(Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).fillMaxWidth().heightIn(max = maxHeight)
                .onSizeChanged { height = it.height.toFloat() }
                .graphicsLayer { translationY = size.height * (1f - progress.value) }
                .pointerInput(progress) {
                    // 与 MetroBottomSheet 同一手势语义：下拉超过 40% 收起，否则回弹。
                    detectVerticalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (progress.value < 0.6f) actions.onClose()
                                else progress.animateTo(1f, SokuouTweens.SheetAppear)
                            }
                        },
                        onVerticalDrag = { change, amount ->
                            change.consume()
                            if (height > 0f) scope.launch { progress.snapTo((progress.value - amount / height).coerceIn(0f, 1f)) }
                        }
                    )
                })
        } else {
            // 空的 pointerInput 让整页参与命中测试，点页面空白处不会穿透到下面的遮罩把页面关掉。
            panel(Modifier.fillMaxSize().graphicsLayer { translationY = size.height * 0.12f * (1f - progress.value); alpha = progress.value }
                .pointerInput(Unit) {})
        }
    }
}

/** 无网络的展示层：状态来自应用级协调器，便于用模拟状态验收真实 Compose 布局。 */
@Composable
fun PlaylistManagementPanel(
    state: PlaylistPanelState, onDraft: ((PlaylistDraft) -> PlaylistDraft) -> Unit, onSubmit: (Long) -> Unit,
    onCreate: () -> Unit, onClose: () -> Unit, onFinish: () -> Unit,
    onVerify: () -> Unit, onBackToEdit: () -> Unit, onReload: () -> Unit, onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    state.request ?: return
    if (state.isPicker()) PlaylistPickerBody(state, onSubmit, onCreate, onReload, modifier)
    else PlaylistPageBody(state, onDraft, onSubmit, onClose, onFinish, onVerify, onBackToEdit, onReload, onOpen, modifier)
}

@Composable
private fun PlaylistPickerBody(
    state: PlaylistPanelState, onSubmit: (Long) -> Unit, onCreate: () -> Unit, onReload: () -> Unit, modifier: Modifier
) {
    val strings = LocalStrings.current
    val p = strings.playlists
    val colors = LocalMetroColors.current
    val type = LocalMetroTypography.current
    Column(modifier.background(colors.surface).navigationBarsPadding()) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp)) {
            MetroText(p.add, style = type.title.copy(fontSize = 22.sp))
            val count = state.request?.songIds?.size ?: 0
            if (count > 0) MetroText(strings.trackCountSongs(count), style = type.caption, color = colors.onSurfaceVariant)
        }
        when {
            state.loading -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { MetroProgressIndicator() }
            else -> LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp)) {
                item(key = "create") {
                    PickerRow(title = p.create, subtitle = null, enabled = !state.busy, onClick = onCreate) {
                        // 强调色铺底、前景按亮度取黑/白：强调色直接当文字色在浅色底上对比度不足（云杉绿约 2.4:1）。
                        Box(Modifier.size(56.dp).background(colors.primary), contentAlignment = Alignment.Center) {
                            MetroIcon(Icons.Default.Add, contentDescription = null, tint = playlistTextColor(colors.primary))
                        }
                    }
                }
                val owned = state.collection.items.filter { it.isOwnedBy(state.ownerId) }
                if (owned.isEmpty()) item(key = "empty") {
                    MetroText(strings.noPlaylists, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
                }
                items(owned, key = { it.id }) { playlist ->
                    val count = if (playlist.trackCount >= 0) strings.trackCount(playlist.trackCount) else p.unknown
                    PickerRow(playlist.name, listOfNotNull(count, p.private.takeIf { playlist.privacy == 10 }).joinToString(" · "),
                        enabled = !state.busy, onClick = { onSubmit(playlist.id) }) {
                        PlaylistArtwork(playlist.coverImgUrl, Modifier.size(56.dp))
                    }
                }
                if (!state.collection.complete) item(key = "incomplete") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        MetroText(p.incomplete, color = colors.onSurfaceVariant, style = type.caption, modifier = Modifier.weight(1f))
                        BarTextAction(strings.retry, enabled = !state.busy, onClick = onReload)
                    }
                }
            }
        }
    }
}

/** 选择器的一行：封面贴左沿，与库里的播放列表行同一版式，只是更紧凑。 */
@Composable
private fun PickerRow(title: String, subtitle: String?, enabled: Boolean, onClick: () -> Unit, leading: @Composable () -> Unit) {
    val colors = LocalMetroColors.current
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            MetroText(title, style = LocalMetroTypography.current.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = colors.onBackground)
            if (subtitle != null) MetroText(subtitle, style = LocalMetroTypography.current.caption, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlaylistPageBody(
    state: PlaylistPanelState, onDraft: ((PlaylistDraft) -> PlaylistDraft) -> Unit, onSubmit: (Long) -> Unit,
    onClose: () -> Unit, onFinish: () -> Unit, onVerify: () -> Unit, onBackToEdit: () -> Unit,
    onReload: () -> Unit, onOpen: (Long) -> Unit, modifier: Modifier
) {
    val strings = LocalStrings.current
    val p = strings.playlists
    val colors = LocalMetroColors.current
    val mode = state.request!!.mode
    Column(modifier.background(colors.background).statusBarsPadding().navigationBarsPadding().imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.widthIn(max = 640.dp).fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically) {
            MetroIconButton(onClick = onClose) {
                MetroIcon(Icons.Default.Close, contentDescription = strings.cancel, tint = colors.onBackground)
            }
            MetroText(when (mode) {
                PlaylistPanelMode.ADD -> p.add
                PlaylistPanelMode.CREATE -> p.create
                PlaylistPanelMode.EDIT -> p.edit
                PlaylistPanelMode.REMOVE -> p.remove
                PlaylistPanelMode.DELETE -> p.delete
                PlaylistPanelMode.REORDER -> p.sortTracks
            }, style = LocalMetroTypography.current.title.copy(fontSize = 22.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp))
        }
        when {
            state.loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { MetroProgressIndicator() }
            state.result != null -> PlaylistResultBody(state, onFinish, onVerify, onBackToEdit, onOpen, Modifier.weight(1f))
            state.problem != null -> Column(Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                MetroText(playlistProblemText(state.problem, p), style = LocalMetroTypography.current.bodyLarge)
                Spacer(Modifier.height(20.dp))
                PlaylistButton(strings.retry, onReload)
            }
            else -> PlaylistEditorBody(state, onDraft, onSubmit, Modifier.weight(1f))
        }
    }
}

/** 只有待确认 / 部分完成 / 失败会停在这里（已确认直接收起并给轻提示）。主动作一个，其余为文字动作。 */
@Composable
private fun PlaylistResultBody(
    state: PlaylistPanelState, onFinish: () -> Unit, onVerify: () -> Unit, onBackToEdit: () -> Unit,
    onOpen: (Long) -> Unit, modifier: Modifier
) {
    val p = LocalStrings.current.playlists
    val colors = LocalMetroColors.current
    val type = LocalMetroTypography.current
    val result = state.result!!
    val mode = state.request!!.mode
    Column(modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        MetroText(when (result.outcome) {
            PlaylistOutcome.CONFIRMED -> p.confirmed
            PlaylistOutcome.PENDING -> if (result.playlistId == null) p.unknownCreate else p.pending
            PlaylistOutcome.PARTIAL -> if (state.request.songIds.isNotEmpty()) p.partial(result.confirmedIds.size, result.unresolvedIds.size)
                else p.partial(result.confirmedFields.count { it != PlaylistAction.CREATE }, 3 - result.confirmedFields.count { it != PlaylistAction.CREATE })
            PlaylistOutcome.FAILED -> p.failed
        }, style = type.title.copy(fontSize = 20.sp))
        result.problem?.let { Spacer(Modifier.height(10.dp)); MetroText(playlistProblemText(it, p), color = colors.onSurfaceVariant) }
        state.backupFile?.let { Spacer(Modifier.height(10.dp)); MetroText(p.backup(it.absolutePath), style = type.caption, color = colors.onSurfaceVariant) }
        Spacer(Modifier.height(24.dp))
        val retryable = result.outcome in listOf(PlaylistOutcome.PENDING, PlaylistOutcome.PARTIAL) && result.playlistId != null
        when {
            retryable -> PlaylistButton(p.verify, onVerify, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
            result.outcome == PlaylistOutcome.FAILED -> PlaylistButton(p.backToEdit, onBackToEdit, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
        }
        if (state.busy) Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            MetroProgressIndicator(sizeDp = 18.dp, strokeDp = 2.dp); Spacer(Modifier.width(12.dp))
            MetroText(p.working, color = colors.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (result.playlistId != null && mode != PlaylistPanelMode.DELETE)
                BarTextAction(p.open, enabled = !state.busy, onClick = { onOpen(result.playlistId) })
            BarTextAction(if (result.outcome == PlaylistOutcome.CONFIRMED) p.done else p.stopTracking,
                enabled = !state.busy, onClick = onFinish)
        }
    }
}

@Composable
private fun PlaylistEditorBody(state: PlaylistPanelState, onDraft: ((PlaylistDraft) -> PlaylistDraft) -> Unit, onSubmit: (Long) -> Unit, modifier: Modifier) {
    val strings = LocalStrings.current
    val p = strings.playlists
    val colors = LocalMetroColors.current
    val mode = state.request!!.mode
    val editing = mode == PlaylistPanelMode.CREATE || mode == PlaylistPanelMode.EDIT
    Column(modifier.widthIn(max = 640.dp).fillMaxWidth()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            if (editing) {
                PlaylistFieldLabel(p.name, "${state.draft.name.length}/40")
                Spacer(Modifier.height(8.dp))
                MetroTextField(state.draft.name, { value -> if (value.length <= 40) onDraft { it.copy(name = value) } },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = p.name }, enabled = !state.busy, singleLine = true)
                Spacer(Modifier.height(24.dp))
                PlaylistFieldLabel(p.description, "${state.draft.description.length}/1000")
                Spacer(Modifier.height(8.dp))
                MetroTextField(state.draft.description, { value -> if (value.length <= 1000) onDraft { it.copy(description = value) } },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = p.description }, enabled = !state.busy, minLines = 3, maxLines = 6)
                Spacer(Modifier.height(24.dp))
                PlaylistFieldLabel(p.visibility)
                Spacer(Modifier.height(8.dp))
                // 两格分段：选中格铺强调色，硬切换（Kanesumi 状态色不做颜色过渡）。
                Row(Modifier.fillMaxWidth().background(colors.surfaceVariant)) {
                    listOf(0 to p.public, 10 to p.private).forEach { (value, label) ->
                        val selected = state.draft.privacy == value
                        PlaylistButton(label, { onDraft { it.copy(privacy = value) } }, enabled = !state.busy,
                            modifier = Modifier.weight(1f), selected = selected,
                            containerColor = if (selected) colors.primary else colors.surfaceVariant,
                            contentColor = if (selected) playlistTextColor(colors.primary) else colors.onBackground)
                    }
                }
            } else {
                MetroText(when (mode) {
                    PlaylistPanelMode.DELETE -> p.deleteHint(state.snapshot?.info?.name.orEmpty())
                    PlaylistPanelMode.REORDER -> p.reorderHint(state.request.songIds.size)
                    else -> p.removeHint(state.request.songIds.size)
                }, style = LocalMetroTypography.current.bodyLarge)
            }
        }
        val label = when (mode) {
            PlaylistPanelMode.CREATE -> p.create
            PlaylistPanelMode.EDIT -> p.save
            PlaylistPanelMode.DELETE -> p.delete
            PlaylistPanelMode.REORDER -> p.saveOrder
            else -> p.remove
        }
        val owned = state.snapshot?.info?.isOwnedBy(state.ownerId) == true
        val enabled = !state.busy && when {
            editing -> state.draft.valid
            mode == PlaylistPanelMode.REORDER -> owned && state.request.songIds.isNotEmpty()
            else -> owned
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            PlaylistButton(label, { onSubmit(0) }, enabled = enabled, modifier = Modifier.fillMaxWidth())
            // 提交中在按钮上叠进度：文字保持原样，语义树里仍是同一个按钮。
            if (state.busy) MetroProgressIndicator(Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
                color = colors.onSurfaceVariant, sizeDp = 18.dp, strokeDp = 2.dp)
        }
    }
}

@Composable
private fun PlaylistFieldLabel(label: String, value: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetroText(label, modifier = Modifier.weight(1f), color = LocalMetroColors.current.onSurfaceVariant,
            style = LocalMetroTypography.current.caption)
        if (value != null) MetroText(value, color = LocalMetroColors.current.onSurfaceVariant,
            style = LocalMetroTypography.current.caption)
    }
}

fun playlistProblemText(problem: PlaylistProblem, p: PlaylistStrings): String = when (problem) {
    PlaylistProblem.LOGIN -> p.login
    PlaylistProblem.PERMISSION -> p.permission
    PlaylistProblem.RATE_LIMIT -> p.rateLimit
    PlaylistProblem.NETWORK -> p.network
    PlaylistProblem.ACCOUNT_CHANGED -> p.accountChanged
    PlaylistProblem.INVALID -> p.invalid
    PlaylistProblem.INCOMPLETE -> p.incomplete
    PlaylistProblem.REJECTED -> p.failed
}

/** 强调色可由用户任意选择，白字在云杉绿或素白上不可读。只为本次新增按钮选择前景。 */
internal fun playlistTextColor(background: Color): Color {
    val value = background.luminance()
    return if ((value + 0.05f) / 0.05f >= 1.05f / (value + 0.05f)) Color.Black else Color.White
}

@Composable
fun PlaylistButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    containerColor: Color = LocalMetroColors.current.primary, contentColor: Color? = null, selected: Boolean? = null) {
    val latestClick by rememberUpdatedState(onClick)
    MetroButton(text, onClick, modifier.clearAndSetSemantics {
        this.text = AnnotatedString(text)
        role = Role.Button
        selected?.let { this.selected = it }
        if (!enabled) disabled() else onClick { latestClick(); true }
    }, enabled, containerColor,
        contentColor = contentColor ?: playlistTextColor(containerColor))
}
