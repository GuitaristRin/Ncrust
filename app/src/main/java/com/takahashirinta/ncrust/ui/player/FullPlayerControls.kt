package com.takahashirinta.ncrust.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.formatDuration
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun FullPlayerControls(
    isPlaying: Boolean,
    showLyrics: Boolean,
    showQueue: Boolean,
    progressFlow: StateFlow<Float>,
    positionFlow: StateFlow<Long>,
    durationFlow: StateFlow<Long>,
    qualityIndexFlow: StateFlow<Int>,
    qualityOptions: List<String>,
    // 当前歌是否为试听片段（45 秒）。徽标位置改显示「试听」，不显示会误导的档位名。
    trialFlow: StateFlow<Boolean>,
    onPlayPause: () -> Unit,
    onPlayPrevious: () -> Unit = {},
    onPlayNext: () -> Unit = {},
    onToggleLyrics: () -> Unit,
    onToggleQueue: () -> Unit,
    onAddToLibrary: () -> Unit = {},
    // 当前歌是否已在收藏库: true 显示对号, 按下走"移出库"分支
    isInLibrary: Boolean = false,
    isBufferingFlow: StateFlow<Boolean>,
    onSeek: (Float) -> Unit = {},
    onNavigateToUser: () -> Unit = {},
    lyricsUnavailable: Boolean = false,
    previousEnabled: Boolean = true,
    // 宽屏左栏用紧凑尺寸：缩小按钮/间距，把纵向空间让给封面区。
    compact: Boolean = false,
    // 时间与进度合行，面板切换交给顶栏。
    tiny: Boolean = false,
    // 宽屏横向布局：进度行 + 操作行扁平铺开，替代手机的竖向大按钮堆叠。
    landscape: Boolean = false
) {
    val strings = LocalStrings.current
    // 触觉反馈:播放/暂停/切歌/开关面板给一个轻振,补足无 ripple 时代的确认感
    val haptic = LocalHapticFeedback.current
    fun tick() = haptic.performHapticFeedback(HapticFeedbackType.LongPress)

    val hPad = if (compact) 16.dp else 24.dp
    val bottomPad = if (compact) 8.dp else 32.dp
    val gapTight = if (compact) 4.dp else 8.dp
    val gapLoose = if (compact) 8.dp else 16.dp
    val ctrlRowPad = if (compact) 2.dp else 16.dp
    val sideBtn = if (compact) 44.dp else 60.dp
    val sideIcon = if (compact) 30.dp else 40.dp
    val playBtn = if (compact) 58.dp else 84.dp
    val playIcon = if (compact) 38.dp else 56.dp
    val sideGap = if (compact) 20.dp else 32.dp
    val toggleBtn = if (compact) 44.dp else 56.dp
    val toggleIcon = if (compact) 26.dp else 32.dp

    if (landscape) {
        // 宽屏横向控件条：进度行（位置 · 进度 · 时长）+ 操作行（面板开关 · 音质 · 传输），
        // 扁平铺开，不再照搬手机的竖向大按钮堆叠。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PositionText(positionFlow = positionFlow, modifier = Modifier)
                Spacer(Modifier.width(8.dp))
                SlimProgressBar(
                    progressFlow = progressFlow,
                    durationFlow = durationFlow,
                    isBufferingFlow = isBufferingFlow,
                    onSeek = onSeek,
                    modifier = Modifier.weight(1f),
                    horizontalPadding = 0.dp,
                )
                Spacer(Modifier.width(8.dp))
                DurationText(durationFlow = durationFlow, modifier = Modifier)
            }
            Spacer(Modifier.height(6.dp))
            // 对称三段：左=面板开关，中=传输（主控居中），右=音质。
            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.align(Alignment.CenterStart),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clickable(enabled = !lyricsUnavailable) {
                                tick()
                                onToggleLyrics()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        MetroIcon(
                            imageVector = Icons.Default.Lyrics,
                            contentDescription = strings.lyricsButton,
                            tint = if (lyricsUnavailable) LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.5f)
                            else if (showLyrics) LocalMetroColors.current.primary else LocalMetroColors.current.onBackground,
                            sizeDp = 24.dp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clickable {
                                tick()
                                onToggleQueue()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        MetroIcon(
                            imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                            contentDescription = strings.queueButton,
                            tint = if (showQueue) LocalMetroColors.current.primary else LocalMetroColors.current.onBackground,
                            sizeDp = 24.dp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clickable {
                                tick()
                                onAddToLibrary()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        MetroIcon(
                            imageVector = if (isInLibrary) Icons.Default.Check else Icons.Default.Add,
                            contentDescription = strings.addToLibraryButton,
                            tint = if (isInLibrary) LocalMetroColors.current.primary else LocalMetroColors.current.onBackground,
                            sizeDp = 24.dp
                        )
                    }
                }
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clickable(enabled = previousEnabled) {
                                tick()
                                onPlayPrevious()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        MetroIcon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = strings.prevButton,
                            tint = if (previousEnabled) LocalMetroColors.current.onBackground else LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.5f),
                            sizeDp = 28.dp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clickable {
                                tick()
                                onPlayPause()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        MetroIcon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) strings.pauseButton else strings.playButton,
                            tint = LocalMetroColors.current.onBackground,
                            sizeDp = 36.dp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clickable {
                                tick()
                                onPlayNext()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        MetroIcon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = strings.nextButton,
                            tint = LocalMetroColors.current.onBackground,
                            sizeDp = 28.dp
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .background(LocalMetroColors.current.surfaceVariant)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onNavigateToUser() }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    QualityLabel(
                        qualityIndexFlow = qualityIndexFlow,
                        trialFlow = trialFlow,
                        options = qualityOptions
                    )
                }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = bottomPad)) {
        if (tiny) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = hPad),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PositionText(positionFlow, Modifier.widthIn(max = 48.dp))
                Spacer(Modifier.width(8.dp))
                SlimProgressBar(
                    progressFlow = progressFlow,
                    durationFlow = durationFlow,
                    isBufferingFlow = isBufferingFlow,
                    onSeek = onSeek,
                    modifier = Modifier.weight(1f),
                    horizontalPadding = 0.dp
                )
                Spacer(Modifier.width(8.dp))
                DurationText(durationFlow, Modifier.widthIn(max = 48.dp))
                Spacer(Modifier.width(8.dp))
                // 最长多语言音质名也只占 64dp；省略号截断，不侵占进度条的权重空间。
                Box(
                    Modifier.widthIn(max = 64.dp)
                        .background(LocalMetroColors.current.surfaceVariant)
                        .clickable { onNavigateToUser() }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    QualityLabel(qualityIndexFlow, trialFlow, qualityOptions)
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = hPad)
            ) {
                // 高频位置文本仅在叶子订阅，不牵连按钮 / 进度条区域。
                PositionText(
                    positionFlow = positionFlow,
                    modifier = Modifier.align(Alignment.CenterStart)
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(LocalMetroColors.current.surfaceVariant)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onNavigateToUser() }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    QualityLabel(
                        qualityIndexFlow = qualityIndexFlow,
                        trialFlow = trialFlow,
                        options = qualityOptions
                    )
                }
                DurationText(
                    durationFlow = durationFlow,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
            Spacer(Modifier.height(gapTight))
            SlimProgressBar(
                progressFlow = progressFlow,
                durationFlow = durationFlow,
                isBufferingFlow = isBufferingFlow,
                onSeek = onSeek
            )
        }
        Spacer(Modifier.height(gapLoose))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = ctrlRowPad),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(sideBtn)
                    .clickable(enabled = previousEnabled) {
                        tick()
                        onPlayPrevious()
                    },
                contentAlignment = Alignment.Center
            ) {
                MetroIcon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = strings.prevButton,
                    tint = if (previousEnabled) LocalMetroColors.current.onBackground else LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.5f),
                    sizeDp = sideIcon
                )
            }
            Spacer(Modifier.width(sideGap))
            Box(
                modifier = Modifier
                    .size(playBtn)
                    .clickable {
                        tick()
                        onPlayPause()
                    },
                contentAlignment = Alignment.Center
            ) {
                MetroIcon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) strings.pauseButton else strings.playButton,
                    tint = LocalMetroColors.current.onBackground,
                    sizeDp = playIcon
                )
            }
            Spacer(Modifier.width(sideGap))
            Box(
                modifier = Modifier
                    .size(sideBtn)
                    .clickable {
                        tick()
                        onPlayNext()
                    },
                contentAlignment = Alignment.Center
            ) {
                MetroIcon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = strings.nextButton,
                    tint = LocalMetroColors.current.onBackground,
                    sizeDp = sideIcon
                )
            }
        }
        if (!tiny) {
            PlayerPanelToggles(
                showLyrics, showQueue, isInLibrary, lyricsUnavailable,
                onToggleLyrics, onToggleQueue, onAddToLibrary,
                toggleBtn, toggleIcon, Modifier.fillMaxWidth()
            )
        }
    }
}

/** Tiny 顶栏与常规底部切换行共用同一组动作及语义。 */
@Composable
fun PlayerPanelToggles(
    showLyrics: Boolean,
    showQueue: Boolean,
    isInLibrary: Boolean,
    lyricsUnavailable: Boolean,
    onToggleLyrics: () -> Unit,
    onToggleQueue: () -> Unit,
    onAddToLibrary: () -> Unit,
    toggleBtn: Dp,
    toggleIcon: Dp,
    modifier: Modifier = Modifier
) {
    val strings = LocalStrings.current
    val haptic = LocalHapticFeedback.current
    fun tick() = haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    Row(modifier, horizontalArrangement = Arrangement.SpaceEvenly) {
        Box(
            modifier = Modifier
                .size(toggleBtn)
                .clickable(enabled = !lyricsUnavailable) {
                    tick()
                    onToggleLyrics()
                },
            contentAlignment = Alignment.Center
        ) {
            MetroIcon(
                imageVector = Icons.Default.Lyrics,
                contentDescription = strings.lyricsButton,
                // 无歌词/未加载时置灰, 语义是该歌不可展开歌词
                tint = if (lyricsUnavailable) LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.5f)
                else if (showLyrics) LocalMetroColors.current.primary else LocalMetroColors.current.onBackground,
                sizeDp = toggleIcon
            )
        }
        Box(
            modifier = Modifier
                .size(toggleBtn)
                .clickable {
                    tick()
                    onToggleQueue()
                },
            contentAlignment = Alignment.Center
        ) {
            MetroIcon(
                imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                contentDescription = strings.queueButton,
                tint = if (showQueue) LocalMetroColors.current.primary else LocalMetroColors.current.onBackground,
                sizeDp = toggleIcon
            )
        }
        Box(
            modifier = Modifier
                .size(toggleBtn)
                .clickable {
                    tick()
                    onAddToLibrary()
                },
            contentAlignment = Alignment.Center
        ) {
            // 已在库 → 对号(按动移出); 不在库 → 加号(按动收藏)
            MetroIcon(
                imageVector = if (isInLibrary) Icons.Default.Check else Icons.Default.Add,
                contentDescription = strings.addToLibraryButton,
                tint = if (isInLibrary) LocalMetroColors.current.primary else LocalMetroColors.current.onBackground,
                sizeDp = toggleIcon
            )
        }
    }
}

@Composable
private fun PositionText(positionFlow: StateFlow<Long>, modifier: Modifier) {
    // 按生命周期收集：熄屏 / 后台时 2Hz 的位置更新不再推动重组。
    val position by positionFlow.collectAsStateWithLifecycle()
    MetroText(
        formatDuration(position),
        color = LocalMetroColors.current.onSurfaceVariant,
        style = TextStyle(fontSize = 12.sp),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

@Composable
private fun DurationText(durationFlow: StateFlow<Long>, modifier: Modifier) {
    val duration by durationFlow.collectAsStateWithLifecycle()
    MetroText(
        formatDuration(duration),
        color = LocalMetroColors.current.onSurfaceVariant,
        style = TextStyle(fontSize = 12.sp),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

@Composable
private fun QualityLabel(
    qualityIndexFlow: StateFlow<Int>,
    trialFlow: StateFlow<Boolean>,
    options: List<String>
) {
    val qualityIndex by qualityIndexFlow.collectAsState()
    val isTrial by trialFlow.collectAsState()
    // 试听片段没有"实际档位"可言：显示档位名会让用户以为音质设置生效了。
    val label = if (isTrial) LocalStrings.current.trialBadge
    else options.getOrElse(qualityIndex) { options.getOrElse(3) { "" } }
    MetroText(
        label,
        color = LocalMetroColors.current.primary,
        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * 迷你条上的音质徽标位：只在试听片段时显示「试听」。
 *
 * 单独做成叶子 Composable 让 [trialFlow] 的订阅只重组这一个 Text——
 * 迷你条挂在 PlayerCard 的常驻子树上，在那里 collect 会让整条随切歌重组。
 */
@Composable
fun TrialBadge(trialFlow: StateFlow<Boolean>, modifier: Modifier = Modifier) {
    val isTrial by trialFlow.collectAsState()
    if (!isTrial) return
    MetroText(
        LocalStrings.current.trialBadge,
        color = LocalMetroColors.current.primary,
        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
        maxLines = 1,
        softWrap = false,
        modifier = modifier
    )
}
