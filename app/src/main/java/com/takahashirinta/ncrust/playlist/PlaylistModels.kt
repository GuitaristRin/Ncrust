package com.takahashirinta.ncrust.playlist

import com.takahashirinta.ncrust.network.PlaylistApi.PlaylistInfo

data class PlaylistSnapshot(
    val info: PlaylistInfo,
    val description: String,
    val trackIds: List<Long>,
    val idsComplete: Boolean
)

data class PlaylistCollection(val items: List<PlaylistInfo>, val complete: Boolean)

/**
 * [REORDER] 没有独立端点：它复用 `manipulate/tracks` 的 `op="update"` 变体提交**整表顺序**，
 * 语义属推断（见 `spec/api/playlist-edit.md`），因此只能在内容完整时使用，且必须整表读回。
 */
enum class PlaylistAction { CREATE, ADD, REMOVE, REORDER, NAME, DESCRIPTION, PRIVACY, DELETE }
enum class PlaylistProblem { LOGIN, PERMISSION, RATE_LIMIT, NETWORK, REJECTED, INCOMPLETE, ACCOUNT_CHANGED, INVALID }
enum class PlaylistOutcome { CONFIRMED, PENDING, PARTIAL, FAILED }

data class PlaylistDraft(val name: String, val description: String, val privacy: Int) {
    val valid: Boolean get() = name.isNotBlank() && name.length <= 40 && description.length <= 1000 && privacy in listOf(0, 10)
}

/** 结果携带目标与已确认内容，不能靠全局 lastError 或缺省的 0 推断成功。 */
data class PlaylistResult(
    val outcome: PlaylistOutcome,
    val playlistId: Long? = null,
    val snapshot: PlaylistSnapshot? = null,
    val confirmedIds: Set<Long> = emptySet(),
    val unresolvedIds: Set<Long> = emptySet(),
    val problem: PlaylistProblem? = null,
    val confirmedFields: Set<PlaylistAction> = emptySet()
)

data class PlaylistCommand(
    val action: PlaylistAction,
    val playlistId: Long = 0,
    val draft: PlaylistDraft? = null,
    val songIds: List<Long> = emptyList()
)

class PlaylistRequestException(val problem: PlaylistProblem) : Exception(problem.name)
