package iris.tools.airlift.monitor

import iris.tools.airlift.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.io.files.Path

private const val PROTOCOL = 4
private const val VERSION_ERROR = "Monitor protocol changed. Restart dispatcher and monitor together."

/** Local authenticated endpoint. Transport supplies only loopback sockets. */
class MonitorServer(private val source: MonitorSource, val endpoint: Path) : AutoCloseable {
    private val token = monitorToken()
    private val listener = monitorTransport().listen()
    private val scope = CoroutineScope(SupervisorJob() + preparationDispatcher())
    private val closed = MutableStateFlow(false)
    private val active = MutableStateFlow<Set<MonitorSocket>>(emptySet())
    private val queue = Channel<MonitorSocket>(16, onUndeliveredElement = { it.close() })

    init {
        try {
            writeConnectionFile(endpoint, mapOf("port" to listener.port.toString(), "token" to token,
                "title" to source.title, "from" to source.from, "to" to source.to, "protocol" to PROTOCOL.toString()))
        } catch (error: Exception) { listener.close(); scope.cancel(); throw error }
        repeat(2) {
            scope.launch {
                for (socket in queue) {
                    active.update { it + socket }
                    try {
                        if (!closed.value) handle(socket)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* A malformed/disconnected client affects only its own request. */ }
                    finally { socket.close(); active.update { it - socket } }
                }
            }
        }
        scope.launch {
            try {
                while (isActive) {
                    val socket = listener.accept()
                    if (closed.value || queue.trySend(socket).isFailure) socket.close()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Listener closed. */ }
        }
    }

    private suspend fun handle(socket: MonitorSocket) {
        val input = MonitorInput(socket)
        val output = MonitorOutput(socket)
        val version = input.readInt()
        if (input.readUTF() != token) return
        if (version != PROTOCOL) { output.writeBoolean(false); Wire.text(output, VERSION_ERROR); output.flush(); return }
        val snapshot = try {
            when (input.readUTF()) {
                "snapshot" -> Unit
                "pause" -> source.pause()
                "resume" -> source.resume()
                "workers" -> source.setWorkerCount(input.readInt())
                else -> error("Unknown monitor command")
            }
            source.snapshot()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            output.writeBoolean(false); Wire.text(output, error.message ?: "Monitor command failed"); output.flush(); return
        }
        output.writeBoolean(true)
        Wire.write(output, snapshot)
        output.flush()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { listener.close() }
        finally {
            scope.cancel()
            queue.cancel()
            active.value.forEach { it.close() }
            deleteConnectionFile(endpoint)
        }
    }
}

class RemoteMonitorSource(endpoint: Path) : MonitorSource {
    private val properties = readConnectionFile(endpoint)
    init { require(properties["protocol"] == PROTOCOL.toString()) { VERSION_ERROR } }
    private val port = requireNotNull(properties["port"]).toInt().also { require(it in 1..65535) }
    private val token = properties["token"] ?: error("Missing monitor token")
    override val title = properties["title"] ?: "RSYNC AIRLIFT"
    override val from = properties["from"] ?: "?"
    override val to = properties["to"] ?: "?"

    private suspend fun request(command: String, count: Int? = null): AirliftSnapshot {
        val socket = monitorTransport().connect(port)
        try {
            val output = MonitorOutput(socket)
            output.writeInt(PROTOCOL); output.writeUTF(token); output.writeUTF(command)
            count?.let { output.writeInt(it) }
            output.flush()
            val input = MonitorInput(socket)
            check(input.readBoolean()) { Wire.text(input) }
            return Wire.read(input)
        } finally { socket.close() }
    }
    override suspend fun snapshot() = request("snapshot")
    override suspend fun pause() { request("pause") }
    override suspend fun resume() { request("resume") }
    override suspend fun setWorkerCount(count: Int) { request("workers", count) }
}
