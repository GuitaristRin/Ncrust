package com.takahashirinta.ncrust.player

import com.google.gson.JsonParser
import com.takahashirinta.ncrust.network.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * spec/fixtures/queue/queue.json 是跨端（Android / Windows）队列语义的权威答案。
 *
 * 这里只跑 `remove` 这一族用例：夹具 README 明确写了 Android 现行实现是**偏离**的
 * （只 clamp 索引，删当前之前的项就错位），其余 op 与夹具逐条对照一致。
 * 其余 op 的实现仍在 MainScreen 的 Compose 局部函数里、没有纯函数入口，
 * 等整份夹具接入时再一起搬过来，所以本测试不假装覆盖它们。
 */
class PlaybackQueueSpecFixtureTest {

    private fun song(id: Long) = SongItem(
        id = id,
        name = "song$id",
        artists = null,
        album = null,
        duration = null
    )

    /** 单测工作目录取决于 Gradle 版本，逐个候选路径找；找不到就报错而不是跳过。 */
    private fun fixtureFile(): File {
        val candidates = listOf(
            File("spec/fixtures/queue/queue.json"),
            File("../spec/fixtures/queue/queue.json"),
            File("app/spec/fixtures/queue/queue.json")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 spec/fixtures/queue/queue.json；尝试过：" +
                candidates.joinToString { it.absolutePath })
    }

    @Test
    fun `remove cases match spec fixture`() {
        val cases = JsonParser.parseString(fixtureFile().readText()).asJsonArray
        var checked = 0
        for (element in cases) {
            val case = element.asJsonObject
            val id = case.get("id").asString
            val input = case.getAsJsonObject("input")
            val op = input.getAsJsonObject("op")
            if (op.get("type").asString != "remove") continue

            val queue = input.getAsJsonArray("queue").map { song(it.asLong) }
            val current = input.get("current").asInt
            val expect = case.getAsJsonObject("expect")

            val result = PlaybackQueueLogic.removeAt(queue, current, op.get("index").asInt)

            assertEquals(
                "$id: 队列内容",
                expect.getAsJsonArray("queue").map { it.asLong },
                result.queue.map { it.id }
            )
            assertEquals("$id: 当前索引", expect.get("current").asInt, result.currentIndex)
            checked++
        }
        assertTrue("夹具里应至少有 4 条 remove 用例，实际 $checked 条", checked >= 4)
    }

    @Test
    fun `remove out of range leaves queue untouched`() {
        val queue = listOf(song(1), song(2))
        val result = PlaybackQueueLogic.removeAt(queue, 1, 5)
        assertEquals(listOf(1L, 2L), result.queue.map { it.id })
        assertEquals(1, result.currentIndex)
    }
}
