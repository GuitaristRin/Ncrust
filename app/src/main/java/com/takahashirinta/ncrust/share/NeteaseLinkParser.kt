package com.takahashirinta.ncrust.share

import java.net.URI

/**
 * 网易云分享链接的统一解析器（纯 JVM，无 Android 依赖，可在 JVM 单测覆盖）。
 *
 * 收件入口（剪贴板 / ACTION_SEND 文本 / ACTION_VIEW URL）全部走这里，避免各处
 * 各自写正则。安全约束：只接受 http/https，只接受网易云已知 host 或短链域名；
 * 未知 host / 非法 scheme 一律 [NeteaseLink.None]，绝不把任意外部 URL 交给网络层。
 */
sealed interface NeteaseLink {
    data class Song(val id: Long) : NeteaseLink
    data class Album(val id: Long) : NeteaseLink
    data class Playlist(val id: Long) : NeteaseLink
    data class Artist(val id: Long) : NeteaseLink
    /** 163cn.tv 短链，需先跟随 302 拿到真实地址再解析。 */
    data object ShortLink : NeteaseLink
    data object None : NeteaseLink
}

object NeteaseLinkParser {

    private val SUPPORTED_HOSTS = setOf("music.163.com", "y.music.163.com")
    private const val SHORT_HOST = "163cn.tv"

    // 只吃 URL 安全字符（ASCII），避免把紧随其后的中文/标点一起吞进 URL。
    private val URL_REGEX = Regex(
        "https?://[A-Za-z0-9\\-._~:/?#\\[\\]@!&()*+,;=%]+",
        RegexOption.IGNORE_CASE
    )

    // 分享文案常以句读/括号结尾，剥掉尾随标点以免污染 URL。
    private val TRIM_CHARS = charArrayOf(
        '.', ',', ';', ':', '!', '?', ')', ']', '}', '>',
        '。', '，', '、', '；', '：', '！', '？', '）', '】', '》', '」', '』'
    )

    /** 从任意文本中提取第一个 http(s) 链接。 */
    fun extractUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val raw = URL_REGEX.find(text)?.value ?: return null
        return raw.trimEnd(*TRIM_CHARS)
    }

    /** 从任意文本解析（先提 URL）。 */
    fun parse(text: String?): NeteaseLink {
        val url = extractUrl(text) ?: return NeteaseLink.None
        return parseUrl(url)
    }

    /** 解析单个 URL。 */
    fun parseUrl(rawUrl: String): NeteaseLink {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return NeteaseLink.None
        val scheme = uri.scheme?.lowercase() ?: return NeteaseLink.None
        if (scheme != "http" && scheme != "https") return NeteaseLink.None
        val host = uri.host?.lowercase() ?: return NeteaseLink.None

        if (host == SHORT_HOST) return NeteaseLink.ShortLink
        if (host !in SUPPORTED_HOSTS) return NeteaseLink.None

        val id = queryLong(rawUrl, "id") ?: return NeteaseLink.None
        // 网页路由可能是 /song?id= 或 /#/song?id=（hash 路由），统一在整串里找路径关键字。
        val lower = rawUrl.lowercase()
        return when {
            lower.contains("/song") -> NeteaseLink.Song(id)
            lower.contains("/album") -> NeteaseLink.Album(id)
            lower.contains("/playlist") -> NeteaseLink.Playlist(id)
            lower.contains("/artist") -> NeteaseLink.Artist(id)
            else -> NeteaseLink.None
        }
    }

    fun songUrl(id: Long) = "https://music.163.com/song?id=$id"
    fun albumUrl(id: Long) = "https://music.163.com/album?id=$id"
    fun playlistUrl(id: Long) = "https://music.163.com/playlist?id=$id"
    fun artistUrl(id: Long) = "https://music.163.com/artist?id=$id"

    private fun queryLong(url: String, key: String): Long? {
        val m = Regex("[?&#/]$key=(\\d+)").find(url) ?: return null
        return m.groupValues[1].toLongOrNull()
    }
}
