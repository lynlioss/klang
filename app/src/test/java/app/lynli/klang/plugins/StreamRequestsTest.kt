package app.lynli.klang.plugins

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class StreamRequestsTest {
    @Test fun cancellingPreparationDoesNotCancelPlaybackAndRepeatedPlaysUseCache() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try { withTimeout(5_000) {
            val streams = StreamRequests<String>(scope, now = { 0 })
            val opened = CompletableDeferred<Unit>()
            val response = CompletableDeferred<String>()
            var calls = 0
            val load: suspend () -> PreparedStream<String> = { calls++; opened.complete(Unit); PreparedStream(response.await(), 100_000) }
            val preparation = async(start = CoroutineStart.UNDISPATCHED) { streams.get("song", load) }
            opened.await()
            val playback = async(start = CoroutineStart.UNDISPATCHED) { streams.get("song", load) }
            preparation.cancelAndJoin()
            assertTrue(playback.isActive)
            response.complete("audio")
            assertEquals("audio", playback.await())
            assertEquals("audio", streams.get("song") { error("Should use the prepared stream") })
            assertEquals(1, calls)
        } } finally { scope.cancel() }
    }

    @Test fun noWaitersCancelsNetworkWorkAndNextAttemptCanResolve() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try { withTimeout(5_000) {
            val streams = StreamRequests<String>(scope, now = { 0 })
            val cancelled = CompletableDeferred<Unit>()
            val caller = async(start = CoroutineStart.UNDISPATCHED) { streams.get("song") {
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            } }
            caller.cancelAndJoin(); cancelled.await()
            assertEquals("new", streams.get("song") { PreparedStream("new", 100_000) })
        } } finally { scope.cancel() }
    }

    @Test fun rejectedUrlRefreshDoesNotEvictOtherPreparedSongs() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val streams = StreamRequests<String>(scope, now = { 0 })
            streams.get("song|a") { PreparedStream("old", 100_000) }
            streams.get("other|a") { PreparedStream("other", 100_000) }
            streams.invalidate("song|")
            assertEquals("fresh", streams.get("song|a") { PreparedStream("fresh", 100_000) })
            assertEquals("other", streams.get("other|a") { error("Unrelated cache entry was evicted") })
        } finally { scope.cancel() }
    }

    @Test fun pageChangesRetainWorkAndQueuePreparationHasPriorityWithinBounds() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try { withTimeout(5_000) {
            val started = mutableListOf<String>()
            val gates = (listOf("a", "b", "c", "d")).associateWith { CompletableDeferred<Unit>() }
            val preparation = StreamPreparation(scope, { id -> started += id; gates.getValue(id).await() }, concurrency = 1, capacity = 2)
            preparation.offer(listOf("a", "b"))
            preparation.offer(listOf("b", "c"))
            preparation.offer(listOf("d"), priority = true)
            assertEquals(listOf("a"), started)
            gates.getValue("a").complete(Unit)
            yield()
            assertEquals(listOf("a", "d"), started)
            gates.getValue("d").complete(Unit)
            yield()
            assertEquals(listOf("a", "d", "b"), started)
            preparation.clear()
        } } finally { scope.cancel() }
    }
    @Test fun bufferingKeepsTappedSongsPreparationAndYieldsOtherWorkers() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try { withTimeout(5_000) {
            val started = mutableListOf<String>()
            val cancelled = mutableListOf<String>()
            val preparation = StreamPreparation(scope, { id ->
                started += id
                try { awaitCancellation() } finally { cancelled += id }
            })
            preparation.offer(listOf("tapped", "other"))
            preparation.pause(true, keep = "tapped")
            assertEquals(listOf("other"), cancelled)
            preparation.offer(listOf("later"))
            assertEquals(listOf("tapped", "other"), started)
            preparation.pause(false)
            assertEquals(listOf("tapped", "other", "other"), started)
            preparation.clear()
        } } finally { scope.cancel() }
    }
}
