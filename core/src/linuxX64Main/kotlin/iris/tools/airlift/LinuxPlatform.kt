@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)
package iris.tools.airlift

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.*
import iris.tools.airlift.posix.*
import iris.tools.airlift.process.LinuxProcessLauncher
import kotlin.native.ref.createCleaner

/** Pass [code] when other calls may have run since the failing one; errno is per-thread but not sticky. */
internal fun posixFailure(operation: String, code: Int = errno): Nothing = error("$operation: ${strerror(code)?.toKString() ?: code.toString()}")

internal actual fun platformLock(): PlatformLock = object : PlatformLock {
    private val mutex = nativeHeap.alloc<pthread_mutex_t>().ptr.also { ptr ->
        memScoped {
            val attributes = alloc<pthread_mutexattr_t>()
            check(pthread_mutexattr_init(attributes.ptr) == 0)
            try {
                check(pthread_mutexattr_settype(attributes.ptr, PTHREAD_MUTEX_RECURSIVE.toInt()) == 0)
                check(pthread_mutex_init(ptr, attributes.ptr) == 0)
            } finally { pthread_mutexattr_destroy(attributes.ptr) }
        }
    }
    private val cleaner = createCleaner(mutex) { pthread_mutex_destroy(it); nativeHeap.free(it) }
    override fun <T> locked(block: () -> T): T {
        check(pthread_mutex_lock(mutex) == 0)
        try { return block() } finally { check(pthread_mutex_unlock(mutex) == 0) }
    }
}

internal actual fun platformEnvironment(): Map<String, String> = buildMap {
    val values = airlift_environ() ?: return@buildMap
    var index = 0
    while (true) {
        val entry = values[index++]?.toKString() ?: break
        val split = entry.indexOf('=')
        if (split > 0) put(entry.substring(0, split), entry.substring(split + 1))
    }
}

internal actual fun platformWorkerDispatcher(): CoroutineDispatcher = Dispatchers.IO
internal actual fun platformProcessLauncher(): iris.tools.airlift.process.ProcessLauncher = LinuxProcessLauncher()

internal actual fun platformClock(): DispatcherClock = object : DispatcherClock {
    private fun nanos(clock: clockid_t): Long = memScoped {
        val value = alloc<timespec>()
        if (clock_gettime(clock, value.ptr) != 0) posixFailure("clock_gettime")
        value.tv_sec * 1_000_000_000L + value.tv_nsec
    }
    override fun wallTimeMillis(): Long = nanos(CLOCK_REALTIME) / 1_000_000
    override fun monotonicNanos(): Long = nanos(CLOCK_MONOTONIC)
}

internal fun linuxAbsolutePath(path: Path): Path {
    val text = path.toString()
    val absolute = if (text.startsWith('/')) text else {
        val cwd = getcwd(null, 0u) ?: posixFailure("getcwd")
        try { cwd.toKString() + "/" + text } finally { free(cwd) }
    }
    val components = mutableListOf<String>()
    for (part in absolute.split('/')) when (part) {
        "", "." -> Unit
        ".." -> if (components.isNotEmpty()) components.removeAt(components.lastIndex)
        else -> components += part
    }
    return Path("/" + components.joinToString("/"))
}
internal actual fun platformPaths(): DispatcherPaths = object : DispatcherPaths {
    override fun absolute(path: Path): Path = linuxAbsolutePath(path)
}

private fun temporaryDirectory(parent: Path, prefix: String): Path {
    require('/' !in prefix && '\u0000' !in prefix)
    val template = (parent.toString().trimEnd('/') + "/" + prefix + "XXXXXX").encodeToByteArray() + byteArrayOf(0)
    return template.usePinned { bytes ->
        val result = mkdtemp(bytes.addressOf(0)) ?: posixFailure("mkdtemp")
        Path(result.toKString())
    }
}

/** The ControlMaster socket name SshSession places inside its directory. */
private const val SSH_SOCKET_NAME = "s"
/** OpenSSH binds the master socket as "<ControlPath>.<16 random characters>" before renaming it. */
private const val SSH_BIND_SUFFIX = 17
/** sockaddr_un.sun_path holds 108 bytes including the terminating NUL. */
private const val SOCKET_PATH_LIMIT = 107

/** TMPDIR when it is absolute and short enough for the ControlMaster socket inside it, else /tmp. */
internal fun sshTemporaryParent(tmpdir: String?, prefix: String): String {
    val parent = tmpdir?.takeIf { it.startsWith('/') }?.trimEnd('/') ?: return "/tmp"
    val socket = "$parent/${prefix}XXXXXX/$SSH_SOCKET_NAME"
    if (socket.encodeToByteArray().size + SSH_BIND_SUFFIX > SOCKET_PATH_LIMIT) return "/tmp"
    return parent.ifEmpty { "/" }
}

internal actual suspend fun platformSshTemporaryDirectory(prefix: String): Path = withContext(Dispatchers.IO) {
    temporaryDirectory(Path(sshTemporaryParent(platformEnvironment()["TMPDIR"], prefix)), prefix)
}
internal actual suspend fun platformDeleteSshDirectory(path: Path) { withContext(Dispatchers.IO) {
    if (airlift_remove_directory(path.toString()) != 0) posixFailure("remove owned directory")
} }
internal actual fun platformLogs(config: AirliftConfig): DispatcherLogStore = object : DispatcherLogStore {
    override suspend fun createRunDirectory(): Path = withContext(Dispatchers.IO) {
        SystemFileSystem.createDirectories(config.logDirectory)
        temporaryDirectory(config.logDirectory, "run-")
    }
    override fun attemptPath(run: Path, worker: Int, taskIndex: Int, attempt: Int) = Path(run, "worker-$worker-task-$taskIndex-attempt-$attempt.log")
}
