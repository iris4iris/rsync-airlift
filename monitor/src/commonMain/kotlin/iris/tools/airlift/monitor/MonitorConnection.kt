package iris.tools.airlift.monitor

import iris.tools.airlift.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.io.files.Path

internal const val PROTOCOL = 4
private const val VERSION_ERROR = "Monitor protocol changed. Restart dispatcher and monitor together."
/** Token and command fields are short ASCII; longer declarations are rejected before their body is read. */
private const val MAX_TOKEN_BYTES = 64
private const val MAX_COMMAND_BYTES = 32

/** Limits for unauthenticated and authenticated connection phases. */
internal data class MonitorServerLimits(
    val connections: Int = 8,
    val requestMillis: Long = 5_000,
    val responseMillis: Long = 30_000,
    val acceptRetryMillis: Long = 1_000,
)

/** Local authenticated endpoint. Transport supplies only loopback sockets.
 * Each connection runs in its own coroutine; at most [MonitorServerLimits.connections] are served at once and
 * further connections are closed immediately. The unauthenticated request phase has a short overall deadline. */
class MonitorServer internal constructor(
    private val source: MonitorSource, val endpoint: Path, private val limits: MonitorServerLimits,
    transport: MonitorTransport,
) : AutoCloseable {
    constructor(source: MonitorSource, endpoint: Path) : this(source, endpoint, MonitorServerLimits(), monitorTransport())

    private val token = monitorToken()
    private val tokenBytes = modifiedUtf8(token)
    private val listener = transport.listen()
    private val scope = CoroutineScope(SupervisorJob() + preparationDispatcher())
    private val closed = MutableStateFlow(false)
    private val active = MutableStateFlow<Set<MonitorSocket>>(emptySet())
    private val permits = Semaphore(limits.connections)

    init {
        try {
            writeConnectionFile(endpoint, mapOf("port" to listener.port.toString(), "token" to token,
                "title" to source.title, "from" to source.from, "to" to source.to, "protocol" to PROTOCOL.toString()))
        } catch (error: Exception) { listener.close(); scope.cancel(); throw error }
        scope.launch { acceptLoop() }
    }

    private suspend fun acceptLoop() {
        var failures = 0
        while (!closed.value) {
            val socket = try { listener.accept() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                // Transient failures such as EMFILE or ECONNABORTED must not end the service.
                if (closed.value) return
                failures++
                delay(minOf(limits.acceptRetryMillis, 25L shl minOf(failures, 6)))
                continue
            }
            failures = 0
            if (closed.value || !permits.tryAcquire()) { socket.close(); continue }
            active.update { it + socket }
            // ATOMIC: cleanup in finally runs even if close() cancels the scope before dispatch.
            scope.launch(start = CoroutineStart.ATOMIC) {
                try { serve(socket) }
                catch (_: TimeoutCancellationException) { /* A slow client loses only its own connection. */ }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* A malformed/disconnected client affects only its own request. */ }
                finally { socket.close(); active.update { it - socket }; permits.release() }
            }
        }
    }

    private suspend fun serve(socket: MonitorSocket) {
        if (closed.value) return
        val input = MonitorInput(socket)
        val output = MonitorOutput(socket)
        val request = withTimeoutOrNull(limits.requestMillis) {
            val version = input.readInt()
            if (!constantTimeEquals(tokenBytes, input.readUTFBytes(MAX_TOKEN_BYTES))) return@withTimeoutOrNull null
            if (version != PROTOCOL) return@withTimeoutOrNull Request.Mismatch
            when (val command = input.readUTF(MAX_COMMAND_BYTES)) {
                "workers" -> Request.Command(command, input.readInt())
                else -> Request.Command(command, null)
            }
        } ?: return
        withTimeout(limits.responseMillis) { respond(request, output) }
    }

    private sealed interface Request {
        data object Mismatch : Request
        data class Command(val name: String, val count: Int?) : Request
    }

    private suspend fun respond(request: Request, output: MonitorOutput) {
        if (request !is Request.Command) { failure(output, VERSION_ERROR); return }
        val snapshot = try {
            when (request.name) {
                "snapshot" -> Unit
                "pause" -> source.pause()
                "resume" -> source.resume()
                "workers" -> {
                    val count = requireNotNull(request.count)
                    require(count in 1..AirliftConfig.MAX_WORKERS) { "Workers must be between 1 and ${AirliftConfig.MAX_WORKERS}" }
                    source.setWorkerCount(count)
                }
                else -> error("Unknown monitor command")
            }
            source.snapshot()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure(output, error.message ?: "Monitor command failed"); return }
        output.writeBoolean(true)
        Wire.write(output, snapshot)
        output.flush()
    }

    private suspend fun failure(output: MonitorOutput, message: String) {
        output.writeBoolean(false); Wire.text(output, message.take(4096)); output.flush()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { listener.close() }
        finally {
            scope.cancel()
            active.value.forEach { it.close() }
            deleteConnectionFile(endpoint)
        }
    }
}

class RemoteMonitorSource(endpoint: Path) : MonitorSource {
    private val properties = readConnectionFile(endpoint)
    init { require(properties["protocol"] == PROTOCOL.toString()) { VERSION_ERROR } }
    private val port = properties["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: error("Invalid monitor port in $endpoint")
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
