package com.takahashirinta.ncrust.update

import com.google.gson.JsonArray
import com.google.gson.JsonParser

/**
 * GitHub Releases 分页与解析（**纯函数**，便于单测）。
 *
 * 发布走滚动班次且历史资产会留在更早的班次里，一次 `per_page` 拉不全；
 * 只看第一页会把落在后面的更高版本漏掉并误判「已最新」。这里：
 *  - [nextPageUrl] 从响应的 `Link` 头里取 `rel="next"`，供 [UpdateService] 翻页；
 *  - [evaluateReleases] 把若干页响应合并后交给 [UpdateChecker.find]。
 *
 * 关键约束：**任何一页无法解析成 JSON 数组都要返回 null**（调用方按失败处理）。
 * 绝不能把解析失败当成「无更新」，否则会清掉上次真实发现的结果。
 */

/** 解析 `Link` 头里的 `rel="next"` 地址；没有下一页 / 头缺失时返回 null。 */
internal fun nextPageUrl(linkHeader: String?): String? {
    if (linkHeader.isNullOrBlank()) return null
    for (part in linkHeader.split(',')) {
        val segments = part.split(';')
        if (segments.size < 2) continue
        val isNext = segments.drop(1).any { it.trim().equals("rel=\"next\"", ignoreCase = true) }
        if (!isNext) continue
        val url = segments[0].trim().removePrefix("<").substringBefore('>')
        if (url.startsWith("http")) return url
    }
    return null
}

/**
 * 由若干页 releases 响应文本判定一次检查结果。
 *
 * 返回 null 表示**解析失败**（空 / 非数组 / 非法 JSON，或当前版本本身解析不出）——
 * 调用方必须把它路由到 `CheckOutcome.Failed`，绝不能当成「已最新」。
 */
internal fun evaluateReleases(pages: List<String?>, current: AppVersion?): CheckOutcome? {
    if (current == null) return null
    val merged = JsonArray()
    for (page in pages) {
        if (page.isNullOrBlank()) return null
        val parsed = runCatching { JsonParser.parseString(page) }.getOrNull() ?: return null
        if (!parsed.isJsonArray) return null
        parsed.asJsonArray.forEach { merged.add(it) }
    }
    return UpdateChecker.find(merged.toString(), current)
        ?.let { CheckOutcome.Found(it) }
        ?: CheckOutcome.UpToDate
}
