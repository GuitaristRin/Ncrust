package com.takahashirinta.ncrust.ui.player

/**
 * 窄屏大封面的几何（px，卡片根坐标系）。三个值都由可用空间算出，交给封面 overlay 的
 * `graphicsLayer` 定位——动画公式本身（progress / lyricAnimProgress 插值）不动。
 */
data class CoverGeometry(
    val sizePx: Float,
    val centerXPx: Float,
    val centerYPx: Float
)

/**
 * 由可用尺寸算窄屏大封面的边长与锚点。纯函数，便于单测。
 *
 * 边长 = min(可用宽度, 可用高度 − 顶栏 − 控件区 − 歌名块 − 最小留白)，水平居中；
 * 纵向优先沿用既有锚点 [preferredCenterYPx]（0.3 屏高 + 24dp），只有会越出可用带时才
 * 夹进带内——这样 Regular 档在普通手机上算出来与改动前逐像素相同。
 *
 * [contentHeightPx] 是内容区底边（卡片根坐标），调用方负责把系统导航栏扣掉；
 * [controlsHeightPx] 是控件区连同它与面板之间那道 Spacer 的实测高度。
 *
 * [titleReservePx] 是为「歌名 / 歌手」块预留的高度，任务书的公式里没有这一项，但歌名块
 * 就落在封面同一段空间的底部：不预留，封面按高度放大后必然盖住歌名（矮屏上实测如此）。
 * Regular 档传 0，保持满宽与既有观感不变。
 *
 * 极端值（控件区比可用高度还高、宽度为 0 等）一律夹到 0，不返回负数——封面尺寸为负会
 * 让 `Modifier.size` 抛异常。
 */
fun narrowCoverGeometry(
    availableWidthPx: Float,
    contentHeightPx: Float,
    topBarBottomPx: Float,
    controlsHeightPx: Float,
    titleReservePx: Float,
    minGapPx: Float,
    preferredCenterYPx: Float
): CoverGeometry {
    val bandTop = topBarBottomPx.coerceAtLeast(0f)
    // 可用带的底边：控件区之上再让出歌名块与最小留白。夹到 bandTop 保证带宽不为负。
    val bandBottom = (contentHeightPx - controlsHeightPx - titleReservePx - minGapPx)
        .coerceAtLeast(bandTop)
    val bandHeight = bandBottom - bandTop

    val size = minOf(availableWidthPx, bandHeight).coerceAtLeast(0f)
    val half = size / 2f
    // size ≤ bandHeight ⇒ minCenterY ≤ maxCenterY，coerceIn 不会抛。
    val centerY = preferredCenterYPx.coerceIn(bandTop + half, bandBottom - half)

    return CoverGeometry(
        sizePx = size,
        centerXPx = availableWidthPx.coerceAtLeast(0f) / 2f,
        centerYPx = centerY
    )
}
