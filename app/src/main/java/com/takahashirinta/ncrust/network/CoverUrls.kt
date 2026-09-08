package com.takahashirinta.ncrust.network

/**
 * 网易图床缩略参数统一处理。
 *
 * 背景: perfetto 实测滚动时单次 decodeBitmap 可达 679ms(磁盘 IO + 解码)。
 * 网易图床支持 ?param=WxH 服务端缩略, 请求小图能让传输 + 解码像素同时
 * 减少 2~4 倍。部分 API 返回的 URL 已带 param(官方认为的最佳尺寸),
 * 此时原样保留, 绝不降级。
 *
 * 显示端与 AppWarmup 预取必须走同一函数——URL 一致才能命中同一份磁盘缓存。
 */
object CoverUrls {

    /** 列表/网格/tile 封面: 160~320dp × 2.6x ≈ 420~840px, 640 足够 */
    fun small(url: String?): String? = applyParam(url, 640)

    /** 全屏播放器/详情页头部封面: 1080 宽屏 */
    fun large(url: String?): String? = applyParam(url, 1080)

    private fun applyParam(url: String?, px: Int): String? {
        if (url == null) return null
        if (!url.startsWith("http")) return url
        // 已带尺寸参数(官方返回的 picUrl 常为 ?param=xxx)不再追加
        if (url.contains("?param=") || url.contains("&param=")) return url
        return if (url.contains('?')) "$url&param=${px}y$px" else "$url?param=${px}y$px"
    }
}