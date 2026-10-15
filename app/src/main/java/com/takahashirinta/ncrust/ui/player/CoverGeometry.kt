package com.takahashirinta.ncrust.ui.player

/** px，卡片根坐标系；动画只消费尺寸与锚点，不改变插值公式。 */
data class CoverGeometry(val sizePx: Float, val centerXPx: Float, val centerYPx: Float)

/**
 * 内容底边扣除控件（含上方 Spacer）、完整标题块、最小留白，得到封面可用带。
 * 每档都预留标题，不能为保留满宽而把 Regular 的标题高度当成 0。
 * 优先保留原锚点（整屏高 * 0.3 + 24dp），只有越出可用带才夹回带内。
 * 调用方传入布局测量值；单测中的 dp 数字仅为估算输入。
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
    val top = topBarBottomPx.coerceAtLeast(0f)
    val bottom = (contentHeightPx - controlsHeightPx.coerceAtLeast(0f) -
        titleReservePx.coerceAtLeast(0f) - minGapPx.coerceAtLeast(0f)).coerceAtLeast(top)
    val width = availableWidthPx.coerceAtLeast(0f)
    val size = minOf(width, bottom - top)
    val minCenter = top + size / 2f
    // 封面恰好占满高度带时，小数像素的加减舍入可能让上界比下界小一个 ULP。
    // coerceIn 不接受倒置区间；保持同一锚点下界，避免非整数 density 下布局崩溃。
    val maxCenter = (bottom - size / 2f).coerceAtLeast(minCenter)
    return CoverGeometry(
        size, width / 2f,
        preferredCenterYPx.coerceIn(minCenter, maxCenter)
    )
}
