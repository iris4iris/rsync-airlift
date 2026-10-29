@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package iris.tools.airlift.monitor

import iris.tools.airlift.io.LinuxFdPoller
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.posix.*
import iris.tools.airlift.monitor.posix.*

private fun remainingMillis(deadline: Long): Long = (deadline - monitorMonotonicNanos()) / 1_000_000

/** Non-blocking socket; EAGAIN registers with the shared poller under [lock], so close() always wakes waiters. */
private class LinuxMonitorSocket(private var fd: Int) : MonitorSocket {
    private val lock = LinuxLock()
    private fun <T> withFd(block: (Int) -> T): T = lock.locked { check(fd >= 0) { "Monitor socket is closed" }; block(fd) }
    suspend fun connected() {
        val deadline = monitorMonotonicNanos() + 1_500_000_000L
        while (true) {
            currentCoroutineContext().ensureActive()
            var wait: LinuxFdPoller.Registration? = null
            val ready = withFd { socket ->
                airlift_connected(socket).also { result ->
                    if (result < 0) { val error = errno; if (error != EINTR) linuxError("connect monitor", error) }
                    else if (result == 0) wait = LinuxFdPoller.writable(socket)
                }
            }
            if (ready > 0) return
            val remaining = remainingMillis(deadline)
            if (remaining <= 0) { wait?.await(0); error("Monitor connection timed out") }
            wait?.await(remaining)
        }
    }
    override suspend fun read(bytes: ByteArray): Int {
        if (bytes.isEmpty()) return 0
        val deadline = monitorMonotonicNanos() + 3_000_000_000L
        try { while (true) {
            currentCoroutineContext().ensureActive()
            var wait: LinuxFdPoller.Registration? = null
            val count = withFd { socket ->
                bytes.usePinned { read(socket, it.addressOf(0), bytes.size.toULong()) }.also {
                    if (it < 0) {
                        val error = errno
                        if (error == EAGAIN) wait = LinuxFdPoller.readable(socket)
                        else if (error != EINTR) linuxError("read monitor", error)
                    }
                }
            }
            if (count >= 0) return if (count == 0L) -1 else count.toInt()
            val remaining = remainingMillis(deadline)
            if (remaining <= 0) { wait?.await(0); error("Monitor read timed out") }
            wait?.await(remaining)
        } } catch (error: Exception) { close(); throw error }
    }
    override suspend fun write(bytes: ByteArray) {
        var offset = 0
        var deadline = monitorMonotonicNanos() + 3_000_000_000L
        try { while (offset < bytes.size) {
            currentCoroutineContext().ensureActive()
            var wait: LinuxFdPoller.Registration? = null
            val count = withFd { socket ->
                bytes.usePinned { airlift_send(socket, it.addressOf(offset), (bytes.size - offset).toULong()) }.also {
                    if (it < 0) {
                        val error = errno
                        if (error == EAGAIN) wait = LinuxFdPoller.writable(socket)
                        else if (error != EINTR) linuxError("write monitor", error)
                    }
                }
            }
            if (count > 0) { offset += count.toInt(); deadline = monitorMonotonicNanos() + 3_000_000_000L; continue }
            val remaining = remainingMillis(deadline)
            if (remaining <= 0) { wait?.await(0); error("Monitor write timed out") }
            wait?.await(remaining)
        } } catch (error: Exception) { close(); throw error }
    }
    override suspend fun flush() {}
    override fun close() {
        lock.locked {
            if (fd >= 0) { val closing = fd; fd = -1; shutdown(closing, SHUT_RDWR); platform.posix.close(closing); LinuxFdPoller.release(closing) }
        }
    }
}

/** Retry delays after resource exhaustion (EMFILE, ENFILE, ENOBUFS, ENOMEM): the listener stays readable then. */
internal fun acceptBackoffMillis(previous: Long): Long = if (previous <= 0) 50 else minOf(previous * 2, 1000)

internal actual fun monitorTransport(): MonitorTransport = object : MonitorTransport {
    override fun listen(): MonitorListener = memScoped {
        val port = alloc<IntVar>()
        val descriptor = airlift_listen(port.ptr)
        if (descriptor < 0) linuxError("listen monitor")
        val boundPort = port.value
        object : MonitorListener {
            private val lock = LinuxLock()
            private var fd = descriptor
            override val port: Int = boundPort
            /** Only close() ends accepting; transient accept failures are retried. */
            override suspend fun accept(): MonitorSocket {
                var backoff = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    var wait: LinuxFdPoller.Registration? = null
                    var retry = 0
                    val accepted = lock.locked {
                        check(fd >= 0) { "Monitor listener is closed" }
                        airlift_accept(fd).also {
                            if (it < 0) {
                                val error = errno
                                if (error == EAGAIN) wait = LinuxFdPoller.readable(fd)
                                else {
                                    retry = airlift_accept_retry(error)
                                    if (retry == 0) linuxError("accept monitor", error)
                                }
                            }
                        }
                    }
                    if (accepted >= 0) return LinuxMonitorSocket(accepted)
                    when (retry) {
                        2 -> { backoff = acceptBackoffMillis(backoff); delay(backoff) }
                        1 -> Unit
                        else -> { backoff = 0; wait?.await() }
                    }
                }
            }
            override fun close() {
                lock.locked {
                    if (fd >= 0) { val closing = fd; fd = -1; platform.posix.close(closing); LinuxFdPoller.release(closing) }
                }
            }
        }
    }
    override suspend fun connect(port: Int): MonitorSocket {
        require(port in 1..65535)
        val fd = airlift_connect(port)
        if (fd < 0) linuxError("connect monitor")
        val socket = LinuxMonitorSocket(fd)
        try { socket.connected(); return socket } catch (error: Exception) { socket.close(); throw error }
    }
}
