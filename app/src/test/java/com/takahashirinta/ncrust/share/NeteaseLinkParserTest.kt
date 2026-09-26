package com.takahashirinta.ncrust.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NeteaseLinkParserTest {

    @Test
    fun `parses plain song album playlist artist`() {
        assertEquals(NeteaseLink.Song(123L), NeteaseLinkParser.parseUrl("https://music.163.com/song?id=123"))
        assertEquals(NeteaseLink.Album(4L), NeteaseLinkParser.parseUrl("https://music.163.com/album?id=4"))
        assertEquals(NeteaseLink.Playlist(5L), NeteaseLinkParser.parseUrl("https://music.163.com/playlist?id=5"))
        assertEquals(NeteaseLink.Artist(6L), NeteaseLinkParser.parseUrl("https://music.163.com/artist?id=6"))
    }

    @Test
    fun `parses hash routed web url`() {
        assertEquals(
            NeteaseLink.Song(42L),
            NeteaseLinkParser.parseUrl("https://music.163.com/#/song?id=42")
        )
    }

    @Test
    fun `parses mobile host and short link`() {
        assertEquals(
            NeteaseLink.Song(7L),
            NeteaseLinkParser.parseUrl("https://y.music.163.com/m/song?id=7")
        )
        assertEquals(NeteaseLink.ShortLink, NeteaseLinkParser.parseUrl("https://163cn.tv/abcDEF"))
    }

    @Test
    fun `rejects unknown hosts and non-http schemes`() {
        assertEquals(NeteaseLink.None, NeteaseLinkParser.parseUrl("https://evil.example.com/song?id=1"))
        assertEquals(NeteaseLink.None, NeteaseLinkParser.parseUrl("ftp://music.163.com/song?id=1"))
        assertEquals(NeteaseLink.None, NeteaseLinkParser.parseUrl("javascript:alert(1)"))
    }

    @Test
    fun `rejects netease url without id`() {
        assertEquals(NeteaseLink.None, NeteaseLinkParser.parseUrl("https://music.163.com/song"))
    }

    @Test
    fun `parses from share text with surrounding punctuation`() {
        val text = "分享单曲：https://music.163.com/song?id=99（来自网易云音乐）"
        assertEquals(NeteaseLink.Song(99L), NeteaseLinkParser.parse(text))
    }

    @Test
    fun `extractUrl returns first url only`() {
        val text = "listen https://music.163.com/song?id=1 and https://music.163.com/song?id=2"
        assertEquals("https://music.163.com/song?id=1", NeteaseLinkParser.extractUrl(text))
    }

    @Test
    fun `extractUrl null for blank or no url`() {
        assertNull(NeteaseLinkParser.extractUrl(null))
        assertNull(NeteaseLinkParser.extractUrl(""))
        assertNull(NeteaseLinkParser.extractUrl("just some words"))
    }
}
