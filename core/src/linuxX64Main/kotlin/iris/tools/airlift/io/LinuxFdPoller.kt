@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package iris.tools.airlift.io

import iris.tools.airlift.platformLock
import iris.tools.airlift.posixFailure
import iris.tools.airlift.posix.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.posix.*

/**
 * Readiness notification for non-blocking descriptors of the Linux Native adapters; not a stable API.
 *
 * One dedicated thread blocks in poll(), so idle waits cost no periodic wakeups and no IO worker threads.
 * Owners register while holding the lock that guards their descriptor, release it, then [Registration.await].
 * After closing a descriptor, the owner calls [release] so that waiters re-check their state instead of
 * waiting on a number that may already belong to another file.
 */
object LinuxFdPoller {
    class Registration internal constructor(internal val fd: Int, internal val events: Short) {
        internal val ready = CompletableDeferred<Unit>()

        /** Resumes when the descriptor is ready, hung up, invalid or released. Cancellation unregisters. */
        suspend fun await() {
            try { ready.await() } finally { unregister(this) }
        }

        /** Returns false when [timeoutMillis] elapses first. */
        suspend fun await(timeoutMillis: Long): Boolean {
            if (timeoutMillis <= 0) { unregister(this); return ready.isCompleted }
            return try { withTimeoutOrNull(timeoutMillis) { ready.await() } != null } finally { unregister(this) }
        }
    }

    private const val SAFETY_TIMEOUT_MILLIS = 10_000
    private val lock = platformLock()
    private val waiting = mutableListOf<Registration>()
    private var wakeRead = -1
    private var wakeWrite = -1
    private var started = false

    fun readable(fd: Int): Registration = register(fd, POLLIN.toShort())
    fun writable(fd: Int): Registration = register(fd, POLLOUT.toShort())

    /** Wakes every waiter registered for [fd]; call after closing it. */
    fun release(fd: Int) {
        val released = lock.locked { waiting.filter { it.fd == fd }.also { waiting.removeAll(it) } }
        released.forEach { it.ready.complete(Unit) }
        if (released.isNotEmpty()) wake()
    }

    private fun register(fd: Int, events: Short): Registration {
        require(fd >= 0)
        val registration = Registration(fd, events)
        lock.locked { startLocked(); waiting += registration }
        wake()
        return registration
    }

    internal fun unregister(registration: Registration) {
        if (lock.locked { waiting.remove(registration) }) wake()
    }

    private fun wake() { if (wakeWrite >= 0) airlift_wake(wakeWrite) }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    private fun startLocked() {
        if (started) return
        memScoped {
            val fds = allocArray<IntVar>(2)
            if (airlift_pipe(fds) != 0) posixFailure("create poller wakeup pipe")
            if (airlift_nonblocking(fds[0]) != 0 || airlift_nonblocking(fds[1]) != 0) {
                val error = errno
                close(fds[0]); close(fds[1])
                posixFailure("configure poller wakeup pipe", error)
            }
            wakeRead = fds[0]; wakeWrite = fds[1]
        }
        started = true
        CoroutineScope(newSingleThreadContext("airlift-fd-poller")).launch { loop() }
    }

    private fun loop() {
        var capacity = 0
        var descriptors: CPointer<pollfd>? = null
        while (true) {
            val current = lock.locked { waiting.toList() }
            val count = current.size + 1
            if (count > capacity) {
                descriptors?.let { nativeHeap.free(it) }
                capacity = maxOf(count, capacity * 2, 16)
                descriptors = nativeHeap.allocArray(capacity)
            }
            val items = requireNotNull(descriptors)
            items[0].fd = wakeRead; items[0].events = POLLIN.toShort(); items[0].revents = 0
            current.forEachIndexed { index, registration ->
                items[index + 1].fd = registration.fd
                items[index + 1].events = registration.events
                items[index + 1].revents = 0
            }
            val result = poll(items, count.convert(), SAFETY_TIMEOUT_MILLIS)
            if (result < 0) {
                if (errno == EINTR) continue
                // Unexpected (for example EINVAL beyond RLIMIT_NOFILE): let owners retry their own system calls.
                complete(current)
                usleep(10_000u)
                continue
            }
            if (items[0].revents.toInt() != 0) airlift_drain(wakeRead)
            complete(current.filterIndexed { index, _ -> items[index + 1].revents.toInt() != 0 })
        }
    }

    private fun complete(ready: List<Registration>) {
        if (ready.isEmpty()) return
        val removed = lock.locked { ready.filter { waiting.remove(it) } }
        removed.forEach { it.ready.complete(Unit) }
    }
}
