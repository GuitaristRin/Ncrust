package com.takahashirinta.ncrust.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.takahashirinta.ncrust.playlist.PlaylistManager
import com.takahashirinta.ncrust.playlist.PlaylistNotice
import com.takahashirinta.ncrust.playlist.PlaylistNoticeKind
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.i18n.PlaylistStrings
import io.github.takahashirinta.kanesumi.anim.sokuou.SokuouTweens
import io.github.takahashirinta.kanesumi.controls.MetroProgressIndicator
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.delay

private data class NoticeContent(val text: String, val busy: Boolean, val serial: Long = 0, val openId: Long? = null)

/** 已确认的结果在提示条上停留的时长；进行中的任务不计时，直到结果出来。 */
private const val NOTICE_HOLD_MS = 2600L

/**
 * 播放列表写入的轻提示条：贴两侧边缘的反色窄条，叠在迷你播放条与底栏之上。
 *
 * 取代「每次写入后停在一页『已确认完成』等用户点完成」：进行中显示「正在同步」（批量删除显示
 * 进度），确认后显示结果并自动收起，新建 / 加入可直接「查看」。待确认、部分完成和失败仍由
 * 面板承接，这里不报喜不报忧之外的状态。进出场用单一进度在 graphicsLayer 中位移 + 淡入。
 */
@Composable
fun PlaylistNoticeHost(
    onOpen: (Long) -> Unit,
    onDeleted: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val notice by PlaylistManager.notice.collectAsState()
    val task by PlaylistManager.state.collectAsState()
    val batch by PlaylistManager.batch.collectAsState()
    val p = LocalStrings.current.playlists

    LaunchedEffect(notice?.serial) {
        val current = notice ?: return@LaunchedEffect
        if (current.kind == PlaylistNoticeKind.DELETED) current.playlistId?.let(onDeleted)
        delay(NOTICE_HOLD_MS)
        PlaylistManager.dismissNotice(current.serial)
    }

    val content = when {
        batch.running -> NoticeContent(p.batchProgress(batch.done, batch.total), busy = true)
        task.working -> NoticeContent(p.working, busy = true)
        else -> notice?.let { NoticeContent(noticeText(it, p), busy = false, serial = it.serial,
            openId = it.playlistId.takeIf { _ -> it.kind == PlaylistNoticeKind.CREATED || it.kind == PlaylistNoticeKind.ADDED }) }
    }
    // 退场期间 content 已为空，继续画最后一条，直到进度回到 0。
    var retained by remember { mutableStateOf<NoticeContent?>(null) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(content != null) {
        if (content != null) progress.animateTo(1f, SokuouTweens.SheetAppear)
        else { progress.animateTo(0f, SokuouTweens.SheetDismiss); retained = null }
    }
    SideEffect { if (content != null) retained = content }
    val shown = content ?: retained ?: return

    val openId = shown.openId
    PlaylistNoticeBar(
        text = shown.text, busy = shown.busy, progress = { progress.value },
        actionLabel = p.view.takeIf { openId != null },
        onAction = { PlaylistManager.dismissNotice(shown.serial); openId?.let(onOpen) },
        onDismiss = { PlaylistManager.dismissNotice(shown.serial) },
        modifier = modifier
    )
}

/** 提示条本体（无状态）：反色窄条，贴两侧边缘；[progress] 只在 graphicsLayer 中读取。 */
@Composable
fun PlaylistNoticeBar(
    text: String, busy: Boolean, progress: () -> Float,
    actionLabel: String?, onAction: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier,
) {
    val colors = LocalMetroColors.current
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                val t = progress()
                translationY = size.height * (1f - t)
                alpha = t
            }
            .background(colors.onBackground)
            .clickable(enabled = !busy, role = Role.Button, onClick = onDismiss)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .heightIn(min = 48.dp)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (busy) {
            MetroProgressIndicator(color = colors.background, sizeDp = 16.dp, strokeDp = 2.dp)
            Spacer(Modifier.width(12.dp))
        }
        MetroText(text, color = colors.background, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = LocalMetroTypography.current.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
        if (actionLabel != null) {
            Box(Modifier.clickable(role = Role.Button, onClick = onAction).padding(horizontal = 12.dp, vertical = 12.dp)) {
                MetroText(actionLabel, color = colors.background,
                    style = LocalMetroTypography.current.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            }
        } else Spacer(Modifier.width(12.dp))
    }
}

private fun noticeText(notice: PlaylistNotice, p: PlaylistStrings): String = when (notice.kind) {
    PlaylistNoticeKind.ADDED -> p.noticeAdded(notice.name)
    PlaylistNoticeKind.REMOVED -> p.noticeRemoved(notice.count)
    PlaylistNoticeKind.REORDERED -> p.noticeReordered
    PlaylistNoticeKind.CREATED -> p.noticeCreated(notice.name)
    PlaylistNoticeKind.SAVED -> p.noticeSaved
    PlaylistNoticeKind.DELETED -> p.noticeDeleted(maxOf(1, notice.count))
}
