package iris.tools.airlift.process

import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Each blocked pipe read or write holds a thread until the child acts. On Dispatchers.IO alone (64 threads)
 * enough workers could park every thread in a read while the writes that feed those children wait in the queue.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal val processIo: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(4096)

/** Blocking pipe operations belong to this adapter, regardless of the caller's dispatcher. */
class JvmProcessLauncher : ProcessLauncher {
    override suspend fun start(spec: ProcessSpec): ChildProcess {
        val child = withContext(NonCancellable) {
            withContext(processIo) {
                val builder = ProcessBuilder(spec.command)
                if (!spec.inheritEnvironment) builder.environment().clear()
                spec.environment.forEach { (key, value) ->
                    if (value == null) builder.environment().remove(key) else builder.environment()[key] = value
                }
                spec.directory?.let { builder.directory(java.io.File(it.toString())) }
                builder.redirectInput(if (spec.input is ProcessRedirect.File)
                    ProcessBuilder.Redirect.from(java.io.File(spec.input.path.toString())) else redirect(spec.input))
                builder.redirectOutput(redirect(spec.output))
                builder.redirectError(redirect(spec.error))
                builder.redirectErrorStream(spec.mergeError)
                JvmChildProcess(builder.start(), spec)
            }
        }
        try { currentCoroutineContext().ensureActive(); return child }
        catch (e: CancellationException) { child.close(); throw e }
    }

    private fun redirect(value: ProcessRedirect): ProcessBuilder.Redirect = when (value) {
        ProcessRedirect.Pipe -> ProcessBuilder.Redirect.PIPE
        ProcessRedirect.Inherit -> ProcessBuilder.Redirect.INHERIT
        is ProcessRedirect.File -> if (value.append) ProcessBuilder.Redirect.appendTo(java.io.File(value.path.toString()))
            else ProcessBuilder.Redirect.to(java.io.File(value.path.toString()))
    }
}

private class JvmChildProcess(private val process: Process, spec: ProcessSpec) : ChildProcess {
    private val ioJob = SupervisorJob()
    private val ioScope = CoroutineScope(ioJob + processIo)
    private val closeMutex = Mutex()
    private var closed = false

    // Cancellation kills the child to unblock OS pipe reads/writes that cannot be interrupted.
    private suspend fun <T> pipe(action: () -> T): T = suspendCancellableCoroutine { continuation ->
        val task = ioScope.launch {
            try { continuation.resume(action()) }
            catch (e: Throwable) { continuation.resumeWithException(e) }
        }
        task.invokeOnCompletion { cause -> if (cause != null) continuation.cancel(cause) }
        continuation.invokeOnCancellation { process.destroyForcibly(); task.cancel() }
    }

    private fun source(stream: InputStream) = object : ProcessByteSource {
        override suspend fun read(buffer: ByteArray): Int {
            require(buffer.isNotEmpty())
            return pipe { stream.read(buffer) }
        }
        override suspend fun close() { pipe { stream.close() } }
    }
    private fun sink(stream: OutputStream) = object : ProcessByteSink {
        override suspend fun write(bytes: ByteArray) { pipe { stream.write(bytes) } }
        override suspend fun flush() { pipe { stream.flush() } }
        override suspend fun close() { pipe { stream.close() } }
    }
    override val stdin = if (spec.input == ProcessRedirect.Pipe) sink(process.outputStream) else null
    override val stdout = if (spec.output == ProcessRedirect.Pipe) source(process.inputStream) else null
    override val stderr = if (!spec.mergeError && spec.error == ProcessRedirect.Pipe) source(process.errorStream) else null
    override suspend fun exitCode(): Int? = try { process.exitValue() } catch (_: IllegalThreadStateException) { null }
    override suspend fun awaitExit(): Int = process.onExit().await().exitValue()
    override suspend fun awaitExit(timeoutMillis: Long): Int? {
        require(timeoutMillis >= 0)
        return exitCode() ?: withTimeoutOrNull(timeoutMillis) { awaitExit() }
    }
    override suspend fun terminate(force: Boolean) {
        withContext(processIo) { if (force) process.destroyForcibly() else process.destroy(); Unit }
    }
    override suspend fun close(): Unit = withContext(NonCancellable) {
        closeMutex.withLock {
            if (closed) return@withLock
            terminate()
            if (awaitExit(2000) == null) { terminate(force = true); awaitExit() }
            withContext(processIo) {
                runCatching { process.outputStream.close() }
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
            }
            ioJob.cancelAndJoin()
            closed = true
        }
    }
}
