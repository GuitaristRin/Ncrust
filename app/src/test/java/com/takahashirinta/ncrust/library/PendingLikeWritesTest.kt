package com.takahashirinta.ncrust.library

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PendingLikeWritesTest {
    @Test
    fun `a second request cannot reach the server before an in-flight request finishes`() = runBlocking {
        withTimeout(3000) {
            val state = PendingLikeWrites()
            val first = state.record(1, true, "account")
            val second = state.record(2, false, "account")
            val release = CompletableDeferred<Unit>()
            val sent = mutableListOf<Long>()
            val one = async(start = CoroutineStart.UNDISPATCHED) {
                state.submit(first, { "account" }, { sent += it.songId; release.await(); true }, { true })
            }
            val two = async(start = CoroutineStart.UNDISPATCHED) {
                state.submit(second, { "account" }, { sent += it.songId; true }, { true })
            }
            assertEquals(listOf(1L), sent)
            release.complete(Unit)
            assertTrue(one.await())
            assertTrue(two.await())
            assertEquals(listOf(1L, 2L), sent)
        }
    }

    @Test
    fun `opposite action during an in-flight write wins and old response cannot clear it`() = runBlocking {
        withTimeout(3000) {
            val state = PendingLikeWrites()
            val first = state.record(42, true, "account")
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val sent = mutableListOf<Boolean>()
            val job = async {
                state.submit(first, { "account" }, {
                    sent += it.desired
                    started.complete(Unit)
                    release.await()
                    true
                }, { true })
            }
            started.await()
            val latest = state.record(42, false, "account")
            release.complete(Unit)
            assertFalse(job.await())
            assertEquals(mapOf(42L to false), state.snapshot())
            assertTrue(state.submit(latest, { "account" }, { sent += it.desired; true }, { true }))
            assertEquals(listOf(true, false), sent)
            assertTrue(state.snapshot().isEmpty())
        }
    }

    @Test
    fun `superseded queued writes are skipped even after toggling back to the original direction`() = runBlocking {
        val state = PendingLikeWrites()
        val oldLike = state.record(42, true, "account")
        val oldUnlike = state.record(42, false, "account")
        val lastLike = state.record(42, true, "account")
        val sent = mutableListOf<Boolean>()
        for (intent in listOf(oldLike, oldUnlike, lastLike)) {
            state.submit(intent, { "account" }, { sent += it.desired; true }, { true })
        }
        assertEquals(listOf(true), sent)
        assertTrue(state.snapshot().isEmpty())
    }

    @Test
    fun `failed writes and failed reads retain the recorded direction`() = runBlocking {
        for ((writeOk, readOk) in listOf(false to true, true to false)) {
            val state = PendingLikeWrites()
            val intent = state.record(42, false, "account")
            assertFalse(state.submit(intent, { "account" }, { writeOk }, { readOk }))
            assertEquals(mapOf(42L to false), state.snapshot())
        }
    }

    @Test
    fun `new intent during verification is retained even when the old read matches`() = runBlocking {
        val state = PendingLikeWrites()
        val old = state.record(42, true, "account")
        assertFalse(state.submit(old, { "account" }, { true }, {
            state.record(42, false, "account")
            true
        }))
        assertEquals(mapOf(42L to false), state.snapshot())
    }

    @Test
    fun `account switch prevents queued writes and stale confirmation`() = runBlocking {
        val state = PendingLikeWrites()
        val intent = state.record(42, true, "first")
        assertFalse(state.submit(intent, { "second" }, { fail("wrong account write"); true }, { true }))
        var credential = "first"
        assertFalse(state.submit(intent, { credential }, { credential = "second"; true }, {
            fail("wrong account verification"); true
        }))
        assertEquals(mapOf(42L to true), state.snapshot())
    }

    @Test
    fun `retry uses persisted direction and cannot replace a newer opposite intent`() {
        val state = PendingLikeWrites(mapOf(42L to true))
        assertEquals(true, state.retry(42, true, "account")?.desired)
        state.record(42, false, "account")
        assertNull(state.retry(42, true, "account"))
        assertEquals(mapOf(42L to false), state.snapshot())
    }

    @Test
    fun `stale cloud refresh preserves pending likes and suppresses pending unlikes`() {
        assertEquals(listOf(99L, 1L), mergePendingLikeIds(listOf(1L, 2L, 1L), mapOf(99L to true, 2L to false)))
        assertEquals(listOf(99L), mergePendingLikeIds(emptyList(), mapOf(99L to true)))
    }

    @Test
    fun `refresh started before a new or just-confirmed action cannot overwrite it`() = runBlocking {
        val state = PendingLikeWrites()
        val beforeWrite = state.revision()
        val intent = state.record(42, true, "account")
        assertFalse(state.applySnapshotIfUnchanged(beforeWrite) { fail("stale snapshot applied") })
        val duringWrite = state.revision()
        assertTrue(state.submit(intent, { "account" }, { true }, { true }))
        assertFalse(state.applySnapshotIfUnchanged(duringWrite) { fail("pre-confirmation snapshot applied") })
        var applied = false
        assertTrue(state.applySnapshotIfUnchanged(state.revision()) { applied = true })
        assertTrue(applied)
    }
}
