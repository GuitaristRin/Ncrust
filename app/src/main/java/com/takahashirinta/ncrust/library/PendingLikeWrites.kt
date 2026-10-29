package com.takahashirinta.ncrust.library

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 写入按序执行；每次意图有独立身份，旧响应不能确认新操作。 */
internal class PendingLikeWrites(initial: Map<Long, Boolean> = emptyMap()) {
    data class Intent(val songId: Long, val desired: Boolean, val revision: Long, val credential: String)

    private val lock = Any()
    private val writer = Mutex()
    private var revision = 0L
    private val pending = initial.mapValuesTo(linkedMapOf()) { (id, desired) ->
        Intent(id, desired, revision, "")
    }

    fun snapshot(): Map<Long, Boolean> = synchronized(lock) {
        pending.mapValues { it.value.desired }
    }

    fun revision(): Long = synchronized(lock) { revision }

    fun applySnapshotIfUnchanged(expected: Long, apply: (Map<Long, Boolean>) -> Unit): Boolean = synchronized(lock) {
        if (revision != expected) false else {
            apply(snapshot())
            true
        }
    }

    fun record(songId: Long, desired: Boolean, credential: String): Intent = synchronized(lock) {
        Intent(songId, desired, ++revision, credential).also { pending[songId] = it }
    }

    fun retry(songId: Long, desired: Boolean, credential: String): Intent? = synchronized(lock) {
        if (pending[songId]?.desired != desired) null else record(songId, desired, credential)
    }

    suspend fun submit(
        intent: Intent,
        currentCredential: () -> String,
        write: suspend (Intent) -> Boolean,
        verify: suspend (Intent) -> Boolean
    ): Boolean = writer.withLock {
        fun current() = synchronized(lock) { pending[intent.songId] == intent } &&
            intent.credential == currentCredential()

        if (!current() || !write(intent) || !current()) return@withLock false
        if (!verify(intent)) return@withLock false
        synchronized(lock) {
            if (!current()) false else {
                pending.remove(intent.songId)
                revision++
                true
            }
        }
    }
}

/** 待确认收藏仍显示为本地意图；读取延迟不能抹掉待同步项或恢复待取消项。 */
internal fun mergePendingLikeIds(cloud: List<Long>, pending: Map<Long, Boolean>): List<Long> =
    (pending.filterValues { it }.keys + cloud).distinct().filter { pending[it] != false }
