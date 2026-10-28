package com.takahashirinta.ncrust.playlist

import com.takahashirinta.ncrust.network.PlaylistApi.PlaylistInfo
import com.takahashirinta.ncrust.network.PlaylistEditApi
import com.takahashirinta.ncrust.network.PlaylistWriteResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PlaylistRepositoryTest {
    private class Gateway : PlaylistGateway {
        var data = PlaylistSnapshot(PlaylistInfo(42, "原名", "", 2, 9, 0, 10), "", listOf(1, 2), true)
        val writes = mutableListOf<PlaylistCommand>()
        var failReads = false
        var deleted = false
        var handler: ((PlaylistCommand) -> PlaylistWriteResult)? = null
        override suspend fun userId() = 9L
        override suspend fun snapshot(id: Long): PlaylistSnapshot {
            if (failReads) throw IOException("read failed")
            return data
        }
        override suspend fun collection(userId: Long) = PlaylistCollection(if (deleted) emptyList() else listOf(data.info), true)
        override suspend fun write(command: PlaylistCommand): PlaylistWriteResult {
            writes += command
            handler?.let { return it(command) }
            when (command.action) {
                PlaylistAction.CREATE -> data = data.copy(info = data.info.copy(name = command.draft!!.name, privacy = command.draft!!.privacy, trackCount = 0), trackIds = emptyList())
                PlaylistAction.NAME -> data = data.copy(info = data.info.copy(name = command.draft!!.name))
                PlaylistAction.DESCRIPTION -> data = data.copy(description = command.draft!!.description)
                PlaylistAction.PRIVACY -> data = data.copy(info = data.info.copy(privacy = command.draft!!.privacy))
                PlaylistAction.ADD -> data = data.copy(trackIds = (data.trackIds + command.songIds).distinct())
                PlaylistAction.REMOVE -> data = data.copy(trackIds = data.trackIds - command.songIds.toSet())
                PlaylistAction.DELETE -> deleted = true
            }
            return PlaylistWriteResult.Success(JSONObject().put("code", 200).put("id", 42))
        }
    }
    @Test fun `create applies description and songs without replaying creation`() = runBlocking {
        val gateway = Gateway()
        val command = PlaylistCommand(PlaylistAction.CREATE, draft = PlaylistDraft("新歌单", "简介", 10), songIds = listOf(5, 6))
        val result = PlaylistRepository(gateway).execute(command)
        assertEquals(PlaylistOutcome.CONFIRMED, result.outcome)
        assertEquals(listOf(PlaylistAction.CREATE, PlaylistAction.DESCRIPTION, PlaylistAction.ADD), gateway.writes.map { it.action })
        assertEquals(setOf(5L, 6L), result.confirmedIds)
    }
    @Test fun `editing updates only changed fields and verifies privacy`() = runBlocking {
        val gateway = Gateway()
        val command = PlaylistCommand(PlaylistAction.NAME, 42, PlaylistDraft("新名", "简介", 0))
        val result = PlaylistRepository(gateway).execute(command)
        assertEquals(PlaylistOutcome.CONFIRMED, result.outcome)
        assertEquals(listOf(PlaylistAction.NAME, PlaylistAction.DESCRIPTION, PlaylistAction.PRIVACY), gateway.writes.map { it.action })
        assertEquals(0, result.snapshot!!.info.privacy)
    }
    @Test fun `duplicate batch does not imply every target is present`() = runBlocking {
        val gateway = Gateway()
        gateway.handler = { PlaylistWriteResult.Duplicate }
        val result = PlaylistRepository(gateway).execute(PlaylistCommand(PlaylistAction.ADD, 42, songIds = listOf(1, 3)))
        assertEquals(PlaylistOutcome.PARTIAL, result.outcome)
        assertEquals(setOf(1L), result.confirmedIds)
        assertEquals(setOf(3L), result.unresolvedIds)
        assertEquals(1, gateway.writes.size)
    }
    @Test fun `lost readback remains pending and retry only reads`() = runBlocking {
        val gateway = Gateway()
        gateway.handler = { gateway.failReads = true; PlaylistWriteResult.Success(JSONObject().put("code", 200)) }
        val repo = PlaylistRepository(gateway)
        val command = PlaylistCommand(PlaylistAction.ADD, 42, songIds = listOf(3))
        assertEquals(PlaylistOutcome.PENDING, repo.execute(command).outcome)
        gateway.failReads = false
        gateway.data = gateway.data.copy(trackIds = listOf(1, 2, 3))
        assertEquals(PlaylistOutcome.CONFIRMED, repo.verify(command).outcome)
        assertEquals(1, gateway.writes.size)
    }
    @Test fun `incomplete snapshot and unknown owner never authorize writes`() = runBlocking {
        for (data in listOf(
            Gateway().data.copy(idsComplete = false),
            Gateway().data.let { it.copy(info = it.info.copy(creatorUserId = 0)) },
            Gateway().data.let { it.copy(info = it.info.copy(specialType = 5)) }
        )) {
            val gateway = Gateway().apply { this.data = data }
            assertEquals(PlaylistOutcome.FAILED, PlaylistRepository(gateway).execute(PlaylistCommand(PlaylistAction.REMOVE, 42, songIds = listOf(1))).outcome)
            assertTrue(gateway.writes.isEmpty())
        }
    }
    @Test fun `HTTP failure with success looking body cannot pass`() {
        assertTrue(PlaylistEditApi.parseWriteResponse(503, "{\"code\":200}") is PlaylistWriteResult.Failure)
    }
    @Test fun `unknown create result has no invented ID and cannot be recreated by verify`() = runBlocking {
        val gateway = Gateway().apply { handler = { throw IOException("connection lost") } }
        val repo = PlaylistRepository(gateway)
        val command = PlaylistCommand(PlaylistAction.CREATE, draft = PlaylistDraft("新名", "", 10))
        val result = repo.execute(command)
        assertEquals(PlaylistOutcome.PENDING, result.outcome)
        assertNull(result.playlistId)
        assertEquals(PlaylistOutcome.PENDING, repo.verify(command).outcome)
        assertEquals(1, gateway.writes.size)
    }
    @Test fun `delete is blocked until backup succeeds`() = runBlocking {
        val gateway = Gateway()
        assertEquals(PlaylistOutcome.FAILED, PlaylistRepository(gateway).execute(PlaylistCommand(PlaylistAction.DELETE, 42)).outcome)
        assertTrue(gateway.writes.isEmpty())
        var backedUp = false
        val result = PlaylistRepository(gateway, backupBeforeDelete = { backedUp = true }).execute(PlaylistCommand(PlaylistAction.DELETE, 42))
        assertTrue(backedUp)
        assertEquals(PlaylistOutcome.CONFIRMED, result.outcome)
    }
    @Test fun `account switch after write prevents remaining mutations`() = runBlocking {
        val gateway = Gateway()
        var current = true
        gateway.handler = { current = false; PlaylistWriteResult.Success(JSONObject().put("code", 200).put("id", 42)) }
        val result = PlaylistRepository(gateway, accountCurrent = { current }).execute(
            PlaylistCommand(PlaylistAction.CREATE, draft = PlaylistDraft("新名", "简介", 10), songIds = listOf(5)))
        assertEquals(PlaylistProblem.ACCOUNT_CHANGED, result.problem)
        assertEquals(1, gateway.writes.size)
    }
    @Test fun `create verification includes songs as well as metadata`() = runBlocking {
        val gateway = Gateway()
        val result = PlaylistRepository(gateway).verify(
            PlaylistCommand(PlaylistAction.CREATE, 42, PlaylistDraft("原名", "", 10), listOf(7)))
        assertNotEquals(PlaylistOutcome.CONFIRMED, result.outcome)
        assertEquals(setOf(7L), result.unresolvedIds)
        assertTrue(gateway.writes.isEmpty())
    }
    @Test fun `snapshot completeness uses declared IDs and handles an empty list`() {
        fun value(ids: String, count: Int) = JSONObject("{\"id\":42,\"trackCount\":$count,\"trackIds\":$ids}")
        assertTrue(PlaylistReadApi.parseSnapshot(value("[]", 0)).idsComplete)
        assertTrue(PlaylistReadApi.parseSnapshot(value("[{\"id\":1}]", 1)).idsComplete)
        assertFalse(PlaylistReadApi.parseSnapshot(value("[{\"id\":1},{\"id\":1}]", 2)).idsComplete)
        assertFalse(PlaylistReadApi.parseSnapshot(value("[{\"id\":1}]", 2)).idsComplete)
        assertFalse(PlaylistReadApi.parseSnapshot(JSONObject("{\"id\":42,\"trackCount\":0}")).idsComplete)
    }
    @Test fun `a later batch failure preserves exact confirmed and unresolved IDs`() = runBlocking {
        val gateway = Gateway()
        gateway.handler = { command ->
            if (gateway.writes.size == 1) {
                gateway.data = gateway.data.copy(trackIds = (gateway.data.trackIds + command.songIds).distinct())
                PlaylistWriteResult.Success(JSONObject().put("code", 200))
            } else PlaylistWriteResult.RateLimited
        }
        val result = PlaylistRepository(gateway).execute(PlaylistCommand(PlaylistAction.ADD, 42, songIds = (1L..600L).toList()))
        assertEquals(PlaylistOutcome.PARTIAL, result.outcome)
        assertEquals((1L..502L).toSet(), result.confirmedIds)
        assertEquals((503L..600L).toSet(), result.unresolvedIds)
        assertEquals(listOf(500, 98), gateway.writes.map { it.songIds.size })
        assertEquals(PlaylistProblem.RATE_LIMIT, result.problem)
    }
    @Test fun `privacy success response without matching readback cannot confirm save`() = runBlocking {
        val gateway = Gateway().apply { handler = { PlaylistWriteResult.Success(JSONObject().put("code", 200)) } }
        val result = PlaylistRepository(gateway).execute(PlaylistCommand(PlaylistAction.NAME, 42, PlaylistDraft("原名", "", 0)))
        assertEquals(PlaylistOutcome.PARTIAL, result.outcome)
        assertFalse(PlaylistAction.PRIVACY in result.confirmedFields)
        assertEquals(listOf(PlaylistAction.PRIVACY), gateway.writes.map { it.action })
    }
    @Test fun `metadata rate limit stops remaining fields and retains readback`() = runBlocking {
        val gateway = Gateway()
        gateway.handler = { command ->
            if (command.action == PlaylistAction.NAME) {
                gateway.data = gateway.data.copy(info = gateway.data.info.copy(name = command.draft!!.name))
                PlaylistWriteResult.Success(JSONObject().put("code", 200))
            } else PlaylistWriteResult.RateLimited
        }
        val result = PlaylistRepository(gateway).execute(PlaylistCommand(PlaylistAction.NAME, 42, PlaylistDraft("新名", "", 0)))
        assertEquals(PlaylistOutcome.PARTIAL, result.outcome)
        assertEquals(PlaylistProblem.RATE_LIMIT, result.problem)
        assertEquals("新名", result.snapshot!!.info.name)
        assertEquals(10, result.snapshot!!.info.privacy)
        assertEquals(2, gateway.writes.size)
    }
    @Test fun `incomplete snapshot cannot confirm removed IDs even during read only retry`() = runBlocking {
        val gateway = Gateway().apply { data = data.copy(idsComplete = false, trackIds = emptyList()) }
        val result = PlaylistRepository(gateway).verify(PlaylistCommand(PlaylistAction.REMOVE, 42, songIds = listOf(1)))
        assertEquals(PlaylistOutcome.PENDING, result.outcome)
        assertTrue(result.confirmedIds.isEmpty())
        assertEquals(setOf(1L), result.unresolvedIds)
        assertTrue(gateway.writes.isEmpty())
    }
}
