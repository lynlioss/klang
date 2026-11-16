package app.lynli.klang.plugins

import kotlinx.coroutines.*

internal data class PreparedStream<T>(val value: T, val expiresAt: Long)

/** Share one request per stream. A cancelled speculative waiter cannot cancel active playback. */
internal class StreamRequests<T>(private val scope: CoroutineScope, capacity: Int = 128,
    private val now: () -> Long = System::currentTimeMillis) {
    private class Pending<T>(val work: Deferred<T>, var waiters: Int = 0)
    private val cache = StreamCache<T>(capacity)
    private val pending = mutableMapOf<String, Pending<T>>()

    suspend fun get(key: String, load: suspend () -> PreparedStream<T>): T {
        val request: Pending<T>
        synchronized(this) {
            cache.get(key, now())?.let { return it }
            request = pending[key] ?: run {
                lateinit var created: Pending<T>
                created = Pending(scope.async(start = CoroutineStart.LAZY) {
                    val stream = load()
                    synchronized(this@StreamRequests) {
                        if (pending[key] === created) cache.put(key, stream.value, stream.expiresAt)
                    }
                    stream.value
                })
                pending[key] = created
                created
            }
            request.waiters++
            request.work.start()
        }
        try { return request.work.await() }
        finally { synchronized(this) {
            if (--request.waiters == 0) {
                if (pending[key] === request) pending.remove(key)
                if (request.work.isActive) request.work.cancel()
            }
        } }
    }

    @Synchronized fun invalidate(prefix: String = "") {
        cache.removePrefix(prefix)
        val keys = pending.keys.filter { it.startsWith(prefix) }
        keys.forEach { pending.remove(it)?.work?.cancel() }
    }
}

/** Bounded background work; changing pages adds priorities without restarting useful requests. */
internal class StreamPreparation(private val scope: CoroutineScope, private val prepare: suspend (String) -> Unit,
    private val concurrency: Int = 2, private val capacity: Int = 24,
    private val now: () -> Long = System::currentTimeMillis) {
    private val waiting = linkedSetOf<String>()
    private val active = mutableMapOf<String, Job>()
    private val failed = mutableMapOf<String, Long>()
    private var paused = false

    @Synchronized fun offer(ids: List<String>, priority: Boolean = false) {
        failed.entries.removeAll { now() - it.value >= 30_000 }
        val added = ids.distinct().filter { it !in active && it !in failed }
        if (priority) {
            val previous = waiting.toList()
            waiting.clear(); waiting.addAll(added); waiting.addAll(previous)
        } else waiting.addAll(added)
        while (waiting.size > capacity) waiting.remove(waiting.last())
        pump()
    }

    @Synchronized fun pause(value: Boolean) { paused = value; if (!paused) pump() }
    @Synchronized fun clear() {
        waiting.clear(); failed.clear()
        val jobs = active.values.toList(); active.clear(); jobs.forEach { it.cancel() }
    }

    private fun pump() {
        while (!paused && active.size < concurrency && waiting.isNotEmpty()) {
            val id = waiting.first(); waiting.remove(id)
            lateinit var job: Job
            job = scope.launch(start = CoroutineStart.LAZY) {
                try { withTimeout(35_000) { prepare(id) } }
                catch (e: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw e
                    synchronized(this@StreamPreparation) { failed[id] = now() }
                }
                catch (_: Exception) { synchronized(this@StreamPreparation) { failed[id] = now() } }
                finally { synchronized(this@StreamPreparation) {
                    if (active[id] === job) active.remove(id)
                    pump()
                } }
            }
            active[id] = job
            job.start()
        }
    }
}
