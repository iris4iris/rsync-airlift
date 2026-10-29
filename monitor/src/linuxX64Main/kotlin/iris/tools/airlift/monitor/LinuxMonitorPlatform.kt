@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)
package iris.tools.airlift.monitor

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.io.files.Path
import iris.tools.airlift.process.LinuxProcessLauncher
import platform.posix.*
import kotlin.native.ref.createCleaner

/** Pass [code] when other calls may have run since the failing one. */
internal fun linuxError(operation: String, code: Int = errno): Nothing = error("$operation: ${strerror(code)?.toKString() ?: code.toString()}")
internal class LinuxLock {
    private val pointer = nativeHeap.alloc<pthread_mutex_t>().ptr.also { check(pthread_mutex_init(it, null) == 0) }
    private val cleaner = createCleaner(pointer) { pthread_mutex_destroy(it); nativeHeap.free(it) }
    fun <T> locked(block: () -> T): T {
        check(pthread_mutex_lock(pointer) == 0)
        try { return block() } finally { check(pthread_mutex_unlock(pointer) == 0) }
    }
}
internal actual fun preparationProcesses(): iris.tools.airlift.process.ProcessLauncher = LinuxProcessLauncher()
internal actual fun preparationDispatcher(): CoroutineDispatcher = Dispatchers.IO
internal actual fun monitorMonotonicNanos(): Long = clockNanos(CLOCK_MONOTONIC)
internal actual fun monitorWallTimeMillis(): Long = clockNanos(CLOCK_REALTIME) / 1_000_000
private fun clockNanos(clock: clockid_t): Long = memScoped {
    val value = alloc<timespec>()
    if (clock_gettime(clock, value.ptr) != 0) linuxError("clock_gettime")
    value.tv_sec * 1_000_000_000L + value.tv_nsec
}
internal actual fun Path.absolutePath(normalize: Boolean): Path {
    val text = toString()
    val absolute = if (text.startsWith('/')) text else {
        val cwd = getcwd(null, 0u) ?: linuxError("getcwd")
        try { cwd.toKString() + "/" + text } finally { free(cwd) }
    }
    if (!normalize) return Path(absolute)
    val parts = mutableListOf<String>()
    for (part in absolute.split('/')) when (part) {
        "", "." -> Unit
        ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
        else -> parts += part
    }
    return Path("/" + parts.joinToString("/"))
}
internal actual fun createMonitorTerminal(title: String, swing: Boolean): MonitorTerminal {
    require(!swing) { "Swing is only available in the JVM version" }
    return LinuxAnsiTerminal()
}
