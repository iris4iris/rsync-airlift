package iris.tools.airlift.monitor

import java.net.*
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Blocking socket calls are confined to IO workers; cancellation closes the owning socket. */
private suspend fun <T> socketIo(close: () -> Unit, discard: (T) -> Unit = {}, operation: () -> T): T =
    suspendCancellableCoroutine { continuation ->
        val task = CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = operation()
                continuation.resume(result, onCancellation = { _, value, _ -> discard(value) })
            } catch (error: Throwable) { continuation.resumeWithException(error) } // Never leave the caller suspended.
        }
        continuation.invokeOnCancellation { close(); task.cancel() }
    }

private class JvmMonitorSocket(private val socket: Socket) : MonitorSocket {
    init { socket.soTimeout = 3000 }
    override suspend fun read(bytes: ByteArray): Int = socketIo(::close) { socket.getInputStream().read(bytes) }
    override suspend fun write(bytes: ByteArray) { socketIo(::close) { socket.getOutputStream().write(bytes) } }
    override suspend fun flush() { socketIo(::close) { socket.getOutputStream().flush() } }
    override fun close() { socket.close() }
}

internal actual fun monitorTransport(): MonitorTransport = object : MonitorTransport {
    override fun listen(): MonitorListener {
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        return object : MonitorListener {
            override val port: Int get() = server.localPort
            override suspend fun accept(): MonitorSocket = socketIo(server::close, { it.close() }) {
                JvmMonitorSocket(server.accept())
            }
            override fun close() { server.close() }
        }
    }
    override suspend fun connect(port: Int): MonitorSocket {
        val socket = Socket()
        try {
            return socketIo(socket::close, { it.close() }) {
                socket.connect(InetSocketAddress("127.0.0.1", port), 1500)
                JvmMonitorSocket(socket)
            }
        } catch (error: Exception) { socket.close(); throw error }
    }
}
