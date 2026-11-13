package com.takahashirinta.ncrust.playlist

import com.takahashirinta.ncrust.network.PlaylistApi.PlaylistInfo
import com.takahashirinta.ncrust.network.PlaylistWriteResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 收藏 / 取消收藏他人歌单：写前读列表决定是否提交，写后只认「我的歌单」列表的读回。 */
class PlaylistSubscriptionTest {
    private class Gateway : PlaylistGateway {
        val me = 9L
        var target = PlaylistInfo(77, "别人的歌单", "", 30, 5, 0, 0)
        val subscribed = mutableSetOf<Long>()
        var complete = true
        var applyWrites = true
        var response: PlaylistWriteResult = PlaylistWriteResult.Success(JSONObject().put("code", 200))
        val writes = mutableListOf<PlaylistCommand>()
        override suspend fun userId() = me
        override suspend fun snapshot(id: Long) = PlaylistSnapshot(target, "", emptyList(), true)
        override suspend fun collection(userId: Long) = PlaylistCollection(
            listOf(PlaylistInfo(1, "我的", "", 1, me, 0, 0)) +
                subscribed.map { target.copy(id = it, subscribed = true) },
            complete
        )
        override suspend fun write(command: PlaylistCommand): PlaylistWriteResult {
            writes += command
            if (applyWrites) when (command.action) {
                PlaylistAction.SUBSCRIBE -> subscribed += command.playlistId
                PlaylistAction.UNSUBSCRIBE -> subscribed -= command.playlistId
                else -> Unit
            }
            return response
        }
    }

    @Test fun `subscribe is confirmed only by reading the playlist back into the collection`() = runBlocking {
        val gateway = Gateway()
        val result = PlaylistRepository(gateway).execute(PlaylistCommand(PlaylistAction.SUBSCRIBE, 77))
        assertEquals(PlaylistOutcome.CONFIRMED, result.outcome)
        assertEquals(listOf(PlaylistAction.SUBSCRIBE), gateway.writes.map { it.action })
    }

    @Test fun `already in the requested state submits nothing`() = runBlocking {
        val gateway = Gateway().apply { subscribed += 77 }
        assertEquals(PlaylistOutcome.CONFIRMED, PlaylistRepository(gateway).execute(PlaylistCommand(PlaylistAction.SUBSCRIBE, 77)).outcome)
        gateway.subscribed.clear()
        assertEquals(PlaylistOutcome.CONFIRMED, PlaylistRepository(gateway).execute(PlaylistCommand(PlaylistAction.UNSUBSCRIBE, 77)).outcome)
        assertTrue(gateway.writes.isEmpty())
    }

    @Test fun `own or unknown-author playlists are never submitted`() = runBlocking {
        for (creator in listOf(9L, 0L)) {
            val gateway = Gateway().apply { target = target.copy(creatorUserId = creator) }
            val result = PlaylistRepository(gateway).execute(PlaylistCommand(PlaylistAction.SUBSCRIBE, 77))
            assertEquals(PlaylistOutcome.FAILED, result.outcome)
            assertEquals(PlaylistProblem.PERMISSION, result.problem)
            assertTrue(gateway.writes.isEmpty())
        }
    }

    @Test fun `incomplete collection before writing refuses to guess the current state`() = runBlocking {
        val gateway = Gateway().apply { complete = false }
        val result = PlaylistRepository(gateway).execute(PlaylistCommand(PlaylistAction.UNSUBSCRIBE, 77))
        assertEquals(PlaylistOutcome.FAILED, result.outcome)
        assertEquals(PlaylistProblem.INCOMPLETE, result.problem)
        assertTrue(gateway.writes.isEmpty())
    }

    @Test fun `accepted write without read back stays pending and retry only reads`() = runBlocking {
        val gateway = Gateway().apply { applyWrites = false }
        val repo = PlaylistRepository(gateway)
        val command = PlaylistCommand(PlaylistAction.SUBSCRIBE, 77)
        assertEquals(PlaylistOutcome.PENDING, repo.execute(command).outcome)
        gateway.subscribed += 77
        assertEquals(PlaylistOutcome.CONFIRMED, repo.verify(command).outcome)
        assertEquals(1, gateway.writes.size)
    }

    @Test fun `unsubscribe needs a complete list without the playlist`() = runBlocking {
        val gateway = Gateway().apply { subscribed += 77 }
        gateway.response = PlaylistWriteResult.Success(JSONObject().put("code", 200))
        val repo = PlaylistRepository(gateway)
        // 写入后列表变成不完整：目标「看不见」不能当成已取消。
        val pending = Gateway().apply { subscribed += 77 }
        val pendingRepo = PlaylistRepository(object : PlaylistGateway by pending {
            override suspend fun write(command: PlaylistCommand): PlaylistWriteResult {
                val result = pending.write(command)
                pending.complete = false
                return result
            }
        })
        assertEquals(PlaylistOutcome.PENDING, pendingRepo.execute(PlaylistCommand(PlaylistAction.UNSUBSCRIBE, 77)).outcome)
        assertEquals(PlaylistOutcome.CONFIRMED, repo.execute(PlaylistCommand(PlaylistAction.UNSUBSCRIBE, 77)).outcome)
    }

    @Test fun `rejected write that did not take effect is a failure, not success`() = runBlocking {
        val gateway = Gateway().apply { applyWrites = false; response = PlaylistWriteResult.Failure(400, "bad") }
        val result = PlaylistRepository(gateway).execute(PlaylistCommand(PlaylistAction.SUBSCRIBE, 77))
        assertEquals(PlaylistOutcome.FAILED, result.outcome)
        assertEquals(PlaylistProblem.REJECTED, result.problem)
    }
}
