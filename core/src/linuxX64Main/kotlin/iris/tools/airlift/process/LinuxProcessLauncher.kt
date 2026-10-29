@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package iris.tools.airlift.process

import iris.tools.airlift.*
import iris.tools.airlift.io.LinuxFdPoller
import iris.tools.airlift.posix.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import platform.posix.*

/** POSIX vfork/exec runs in a C shim: the child never enters the Kotlin runtime. */
class LinuxProcessLauncher : ProcessLauncher {
    override suspend fun start(spec: ProcessSpec): ChildProcess {
        val child = withContext(NonCancellable) { withContext(Dispatchers.IO) { spawn(spec) } }
        try { currentCoroutineContext().ensureActive(); return child }
        catch (error: CancellationException) { child.close(); throw error }
    }

    private fun spawn(spec: ProcessSpec): ChildProcess = memScoped {
        val environment = if (spec.inheritEnvironment) platformEnvironment().toMutableMap() else mutableMapOf()
        spec.environment.forEach { (key, value) -> if (value == null) environment.remove(key) else environment[key] = value }
        val directory = spec.directory?.let(::linuxAbsolutePath)
        val executable = spec.command.first().let { name ->
            if ('/' in name) (if (name.startsWith('/')) name else (directory ?: linuxAbsolutePath(Path("."))).toString() + "/" + name)
            else (environment["PATH"] ?: "/bin:/usr/bin").split(':').map { entry ->
                val base = if (entry.startsWith('/')) entry else (directory ?: linuxAbsolutePath(Path("."))).toString() + "/" + entry
                base.trimEnd('/') + "/" + name
            }.firstOrNull { access(it, X_OK) == 0 } ?: error("Executable not found: $name")
        }
        fun strings(values: List<String>): CPointer<CPointerVar<ByteVar>> = allocArray<CPointerVar<ByteVar>>(values.size + 1).also { array ->
            values.forEachIndexed { i, value -> array[i] = value.cstr.ptr }; array[values.size] = null
        }
        val redirects = listOf(spec.input, spec.output, spec.error)
        val modes = allocArray<IntVar>(3)
        val paths = allocArray<CPointerVar<ByteVar>>(3)
        redirects.forEachIndexed { i, redirect ->
            modes[i] = when (redirect) { ProcessRedirect.Pipe -> 0; ProcessRedirect.Inherit -> 1; is ProcessRedirect.File -> if (redirect.append) 3 else 2 }
            paths[i] = (redirect as? ProcessRedirect.File)?.path?.let { path ->
                val text = path.toString()
                (if (text.startsWith('/')) text else (directory ?: linuxAbsolutePath(Path("."))).toString() + "/" + text).cstr.ptr
            }
        }
        val fds = allocArray<IntVar>(3)
        val error = alloc<IntVar>()
        val pid = airlift_spawn(executable, strings(spec.command), strings(environment.map { "${it.key}=${it.value}" }), directory?.toString(),
            modes, paths, if (spec.mergeError) 1 else 0, fds, error.ptr)
        check(pid >= 0) { "Cannot start ${spec.command.first()}: ${strerror(error.value)?.toKString()}" }
        LinuxChildProcess(pid, fds[0], fds[1], fds[2])
    }
}

private class LinuxChildProcess(private val pid: Int, input: Int, output: Int, error: Int) : ChildProcess {
    private val state = Mutex()
    private val closing = Mutex()
    private var result: Int? = null
    private var closed = false
    private val inputPipe = input.takeIf { it >= 0 }?.let(::Pipe)
    private val outputPipe = output.takeIf { it >= 0 }?.let(::Pipe)
    private val errorPipe = error.takeIf { it >= 0 }?.let(::Pipe)
    override val stdin: ProcessByteSink? = inputPipe?.let { pipe -> object : ProcessByteSink {
        override suspend fun write(bytes: ByteArray) = pipe.writeBytes(bytes)
        override suspend fun flush() {}
        override suspend fun close() = pipe.closePipe()
    } }
    override val stdout: ProcessByteSource? = outputPipe?.source()
    override val stderr: ProcessByteSource? = errorPipe?.source()

    override suspend fun exitCode(): Int? = state.withLock {
        if (result == null) memScoped {
            val status = alloc<IntVar>()
            var waited: Int
            do { waited = waitpid(pid, status.ptr, WNOHANG) } while (waited < 0 && errno == EINTR)
            if (waited < 0) posixFailure("waitpid")
            if (waited == pid) result = if (status.value and 127 == 0) (status.value shr 8) and 255 else 128 + (status.value and 127)
        }
        result
    }
    /** Polls waitpid with a growing pause: prompt for short-lived children, about 20 checks a second after that. */
    override suspend fun awaitExit(): Int {
        var pause = 1L
        while (true) { exitCode()?.let { return it }; delay(pause); pause = minOf(pause * 2, 50L) }
    }
    override suspend fun awaitExit(timeoutMillis: Long): Int? = withTimeoutOrNull(timeoutMillis) { awaitExit() }
    override suspend fun terminate(force: Boolean) { state.withLock {
        if (result == null && kill(pid, if (force) SIGKILL else SIGTERM) != 0 && errno != ESRCH) posixFailure("kill")
    } }
    override suspend fun close() { withContext(NonCancellable) { closing.withLock {
        if (!closed) {
            try {
                if (exitCode() == null) {
                    terminate(false)
                    if (awaitExit(2000) == null) { terminate(true); awaitExit() }
                }
            } finally {
                inputPipe?.closePipe(); outputPipe?.closePipe(); errorPipe?.closePipe()
                closed = true
            }
        }
    } } }

    /** Non-blocking descriptor; EAGAIN waits register with [LinuxFdPoller] under [lock], before close can run. */
    private inner class Pipe(private var fd: Int) {
        private val lock = Mutex()
        fun source() = object : ProcessByteSource {
            override suspend fun read(buffer: ByteArray): Int = readBytes(buffer)
            override suspend fun close() = closePipe()
        }
        suspend fun closePipe() {
            lock.withLock {
                if (fd >= 0) { val closing = fd; fd = -1; platform.posix.close(closing); LinuxFdPoller.release(closing) }
            }
        }
        suspend fun readBytes(bytes: ByteArray): Int {
            if (bytes.isEmpty()) return 0
            try { while (true) {
                currentCoroutineContext().ensureActive()
                var wait: LinuxFdPoller.Registration? = null
                val count = lock.withLock {
                    check(fd >= 0) { "Process pipe is closed" }
                    bytes.usePinned { read(fd, it.addressOf(0), bytes.size.toULong()) }.also {
                        if (it < 0) {
                            val error = errno
                            if (error == EAGAIN) wait = LinuxFdPoller.readable(fd)
                            else if (error != EINTR) posixFailure("read process pipe", error)
                        }
                    }
                }
                if (count >= 0) return if (count == 0L) -1 else count.toInt()
                wait?.await()
            } } catch (cancelled: CancellationException) { withContext(NonCancellable) { terminate(true) }; throw cancelled }
        }
        suspend fun writeBytes(bytes: ByteArray) {
            var offset = 0
            try { while (offset < bytes.size) {
                currentCoroutineContext().ensureActive()
                var wait: LinuxFdPoller.Registration? = null
                val count = lock.withLock {
                    check(fd >= 0) { "Process pipe is closed" }
                    bytes.usePinned { airlift_write(fd, it.addressOf(offset), (bytes.size - offset).toULong()) }.also {
                        if (it < 0) {
                            val error = errno
                            if (error == EAGAIN) wait = LinuxFdPoller.writable(fd)
                            else if (error != EINTR) posixFailure("write process pipe", error)
                        }
                    }
                }
                if (count > 0) offset += count.toInt() else wait?.await()
            } } catch (cancelled: CancellationException) { withContext(NonCancellable) { terminate(true) }; throw cancelled }
        }
    }
}
