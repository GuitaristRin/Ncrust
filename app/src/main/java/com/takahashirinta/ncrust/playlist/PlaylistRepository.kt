package com.takahashirinta.ncrust.playlist

import com.takahashirinta.ncrust.network.PlaylistEditApi
import com.takahashirinta.ncrust.network.PlaylistWriteResult
import kotlinx.coroutines.CancellationException
import java.io.IOException

interface PlaylistGateway {
    suspend fun userId(): Long
    suspend fun snapshot(id: Long): PlaylistSnapshot
    suspend fun collection(userId: Long): PlaylistCollection
    suspend fun write(command: PlaylistCommand): PlaylistWriteResult
}

class NetworkPlaylistGateway(private val cookie: String) : PlaylistGateway {
    private val reads = PlaylistReadApi(cookie)
    override suspend fun userId() = reads.userId()
    override suspend fun snapshot(id: Long) = reads.snapshot(id)
    override suspend fun collection(userId: Long) = reads.collection(userId)
    override suspend fun write(command: PlaylistCommand) = PlaylistEditApi.execute(command, cookie)
}

/** 先读取权限，按动作提交，再读取目标状态。未知结果不会触发第二次写入。 */
class PlaylistRepository(
    private val gateway: PlaylistGateway,
    private val accountCurrent: () -> Boolean = { true },
    private val backupBeforeDelete: suspend (PlaylistSnapshot) -> Unit = { throw PlaylistRequestException(PlaylistProblem.INVALID) }
) {
    private fun guard() {
        if (!accountCurrent()) throw PlaylistRequestException(PlaylistProblem.ACCOUNT_CHANGED)
    }

    private suspend fun owned(id: Long, uid: Long): PlaylistSnapshot {
        guard()
        val snapshot = gateway.snapshot(id)
        guard()
        if (!snapshot.info.isOwnedBy(uid)) throw PlaylistRequestException(PlaylistProblem.PERMISSION)
        return snapshot
    }

    suspend fun execute(command: PlaylistCommand): PlaylistResult {
        var target = command.playlistId.takeIf { it > 0 }
        var submitted = false
        try {
            guard()
            val uid = gateway.userId()
            if (uid <= 0) return failed(PlaylistProblem.LOGIN)
            guard()
            if (command.action == PlaylistAction.CREATE) {
                if (command.draft?.valid != true) return failed(PlaylistProblem.INVALID)
                submitted = true
                val result = submit(command.copy(songIds = emptyList()))
                guard()
                if (result !is PlaylistWriteResult.Success) return PlaylistResult(
                    if (result is PlaylistWriteResult.NetworkError) PlaylistOutcome.PENDING else PlaylistOutcome.FAILED,
                    problem = problemOf(result)
                )
                target = result.raw.optLong("id").takeIf { it > 0 }
                    ?: result.raw.optJSONObject("playlist")?.optLong("id")?.takeIf { it > 0 }
                if (target == null) return PlaylistResult(PlaylistOutcome.PENDING, problem = PlaylistProblem.INCOMPLETE)
                val snapshot = owned(target, uid)
                val edited = edit(command.copy(playlistId = target, songIds = emptyList()), snapshot)
                if (edited.outcome != PlaylistOutcome.CONFIRMED) return edited.copy(unresolvedIds = command.songIds.toSet(), confirmedFields = edited.confirmedFields + PlaylistAction.CREATE)
                if (command.songIds.isNotEmpty()) {
                    val added = tracks(command.copy(action = PlaylistAction.ADD, playlistId = target), owned(target, uid), uid)
                    return added.copy(outcome = if (added.outcome == PlaylistOutcome.FAILED) PlaylistOutcome.PARTIAL else added.outcome,
                        confirmedFields = added.confirmedFields + PlaylistAction.CREATE)
                }
                return edited.copy(confirmedFields = edited.confirmedFields + PlaylistAction.CREATE)
            }
            if (target == null) return failed(PlaylistProblem.INVALID)
            val snapshot = owned(target, uid)
            guard()
            return when (command.action) {
                PlaylistAction.ADD, PlaylistAction.REMOVE -> tracks(command, snapshot, uid)
                PlaylistAction.NAME, PlaylistAction.DESCRIPTION, PlaylistAction.PRIVACY -> edit(command, snapshot)
                PlaylistAction.DELETE -> {
                    if (!snapshot.idsComplete) return failed(PlaylistProblem.INCOMPLETE, target)
                    backupBeforeDelete(snapshot)
                    guard()
                    val written = submit(command)
                    verify(command, target, problemOf(written))
                }
                else -> failed(PlaylistProblem.INVALID, target)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val problem = (error as? PlaylistRequestException)?.problem ?: PlaylistProblem.NETWORK
            return PlaylistResult(if (submitted && problem != PlaylistProblem.ACCOUNT_CHANGED) PlaylistOutcome.PENDING else PlaylistOutcome.FAILED,
                playlistId = target, problem = problem)
        }
    }

    private suspend fun edit(command: PlaylistCommand, before: PlaylistSnapshot): PlaylistResult {
        val draft = command.draft ?: return failed(PlaylistProblem.INVALID, command.playlistId)
        if (!draft.valid) return failed(PlaylistProblem.INVALID, command.playlistId)
        val changes = buildList {
            if (before.info.name != draft.name.trim()) add(PlaylistAction.NAME)
            if (before.description != draft.description) add(PlaylistAction.DESCRIPTION)
            if (before.info.privacy != draft.privacy) add(PlaylistAction.PRIVACY)
        }
        var failure: PlaylistProblem? = null
        for (action in changes) {
            guard()
            val result = submit(command.copy(action = action, songIds = emptyList()))
            if (result !is PlaylistWriteResult.Success) { failure = problemOf(result); break }
        }
        return verify(command, command.playlistId, failure)
    }

    private suspend fun tracks(command: PlaylistCommand, initial: PlaylistSnapshot, uid: Long): PlaylistResult {
        val targets = command.songIds.distinct()
        if (targets.isEmpty() || targets.any { it <= 0 }) return failed(PlaylistProblem.INVALID, command.playlistId)
        if (!initial.idsComplete) return failed(PlaylistProblem.INCOMPLETE, command.playlistId)
        var snapshot = initial
        val add = command.action == PlaylistAction.ADD
        val remaining = targets.filter { (it in initial.trackIds) != add }
        for (batch in remaining.chunked(500)) {
            guard()
            val result = submit(command.copy(songIds = batch))
            guard()
            snapshot = try { owned(command.playlistId, uid) } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if ((error as? PlaylistRequestException)?.problem == PlaylistProblem.ACCOUNT_CHANGED) throw error
                return trackResult(command, snapshot, PlaylistProblem.NETWORK, forcePending = true)
            }
            if (!snapshot.idsComplete) return trackResult(command, snapshot, PlaylistProblem.INCOMPLETE, forcePending = true)
            // 重复响应只允许在 add 下进入读回判断，不能把批量 502 当作整批成功。
            val accepted = result is PlaylistWriteResult.Success || (add && result == PlaylistWriteResult.Duplicate)
            if (!accepted || batch.any { (it in snapshot.trackIds) != add }) {
                return trackResult(command, snapshot, problemOf(result))
            }
        }
        return trackResult(command, snapshot, null)
    }

    private fun trackResult(command: PlaylistCommand, snapshot: PlaylistSnapshot, problem: PlaylistProblem?, forcePending: Boolean = false): PlaylistResult {
        val targets = command.songIds.toSet()
        val confirmed = if (command.action == PlaylistAction.REMOVE && !snapshot.idsComplete) emptySet()
            else targets.filter { (it in snapshot.trackIds) == (command.action == PlaylistAction.ADD) }.toSet()
        val unresolved = targets - confirmed
        return PlaylistResult(
            if (forcePending) PlaylistOutcome.PENDING else outcome(confirmed.size, unresolved.size, problem),
            command.playlistId, snapshot, confirmed, unresolved, problem
        )
    }

    /** 只重新读取，绝不重放创建、删除或某个已提交的批次。 */
    suspend fun verify(command: PlaylistCommand, id: Long = command.playlistId, failure: PlaylistProblem? = null): PlaylistResult {
        if (id <= 0) return PlaylistResult(PlaylistOutcome.PENDING, problem = PlaylistProblem.INCOMPLETE)
        try {
            guard()
            val uid = gateway.userId()
            if (uid <= 0) return failed(PlaylistProblem.LOGIN, id)
            if (command.action == PlaylistAction.DELETE) return verifyDelete(id, uid, failure)
            val snapshot = owned(id, uid)
            if (command.action == PlaylistAction.ADD || command.action == PlaylistAction.REMOVE) {
                return trackResult(command, snapshot, failure, forcePending = !snapshot.idsComplete)
            }
            val draft = command.draft ?: return failed(PlaylistProblem.INVALID, id)
            val fields = buildSet {
                if (snapshot.info.name == draft.name.trim()) add(PlaylistAction.NAME)
                if (snapshot.description == draft.description) add(PlaylistAction.DESCRIPTION)
                if (snapshot.info.privacy == draft.privacy) add(PlaylistAction.PRIVACY)
            }
            val targets = command.songIds.toSet()
            val confirmed = if (command.action == PlaylistAction.CREATE) targets.intersect(snapshot.trackIds.toSet()) else emptySet()
            val unresolved = targets - confirmed
            return PlaylistResult(
                if (targets.isNotEmpty() && !snapshot.idsComplete) PlaylistOutcome.PENDING
                else outcome(fields.size + confirmed.size, 3 - fields.size + unresolved.size, failure),
                id, snapshot, confirmed, unresolved, failure, fields)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val problem = (error as? PlaylistRequestException)?.problem ?: PlaylistProblem.NETWORK
            return PlaylistResult(if (problem == PlaylistProblem.ACCOUNT_CHANGED) PlaylistOutcome.FAILED else PlaylistOutcome.PENDING,
                id, problem = problem)
        }
    }

    private suspend fun verifyDelete(id: Long, uid: Long, failure: PlaylistProblem?): PlaylistResult {
        guard()
        val list = gateway.collection(uid)
        guard()
        val absent = list.complete && list.items.none { it.id == id }
        return PlaylistResult(if (absent) PlaylistOutcome.CONFIRMED else PlaylistOutcome.PENDING, id,
            problem = if (list.complete) failure else PlaylistProblem.INCOMPLETE)
    }

    private suspend fun submit(command: PlaylistCommand): PlaylistWriteResult {
        guard()
        return try { gateway.write(command) } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: IOException) { PlaylistWriteResult.NetworkError(error) }
    }

    companion object {
        fun problemOf(result: PlaylistWriteResult): PlaylistProblem? = when (result) {
            is PlaylistWriteResult.Success -> null
            PlaylistWriteResult.RateLimited -> PlaylistProblem.RATE_LIMIT
            PlaylistWriteResult.IllegalRequest -> PlaylistProblem.PERMISSION
            is PlaylistWriteResult.NetworkError -> PlaylistProblem.NETWORK
            else -> when (result.businessCode) { 301 -> PlaylistProblem.LOGIN; 401, 403 -> PlaylistProblem.PERMISSION; else -> PlaylistProblem.REJECTED }
        }
        private fun failed(problem: PlaylistProblem, id: Long? = null) = PlaylistResult(PlaylistOutcome.FAILED, id, problem = problem)
        private fun outcome(confirmed: Int, unresolved: Int, problem: PlaylistProblem?) = when {
            unresolved == 0 -> PlaylistOutcome.CONFIRMED
            confirmed > 0 -> PlaylistOutcome.PARTIAL
            problem != null && problem != PlaylistProblem.NETWORK -> PlaylistOutcome.FAILED
            else -> PlaylistOutcome.PENDING
        }
    }
}
