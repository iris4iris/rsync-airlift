@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package iris.tools.airlift

import iris.tools.airlift.io.LinuxFdPoller
import iris.tools.airlift.posix.*
import iris.tools.airlift.process.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.posix.*
import kotlin.test.*

/** Child-side hygiene of the vfork spawn path and readiness waiting. */
class LinuxSpawnTest {
    private val launcher = LinuxProcessLauncher()

    private suspend fun ProcessByteSource.text(): String {
        val result = mutableListOf<Byte>()
        val bytes = ByteArray(4096)
        while (true) { val count = read(bytes); if (count < 0) return result.toByteArray().decodeToString(); repeat(count) { result += bytes[it] } }
    }
    private suspend fun output(vararg command: String): String {
        val child = launcher.start(ProcessSpec(command.toList()))
        try {
            child.stdin!!.close()
            val text = child.stdout!!.text()
            assertEquals(0, child.awaitExit(), text)
            return text
        } finally { child.close() }
    }
    private fun status(text: String, field: String): ULong =
        text.lines().first { it.startsWith("$field:") }.substringAfter(':').trim().toULong(16)

    /** Spawns on the calling thread, whose signal mask the test controls; returns stdout. */
    private fun spawnHere(command: List<String>): String = memScoped {
        fun strings(values: List<String>) = allocArray<CPointerVar<ByteVar>>(values.size + 1).also { array ->
            values.forEachIndexed { i, value -> array[i] = value.cstr.ptr }; array[values.size] = null
        }
        val modes = allocArray<IntVar>(3).also { it[0] = 2; it[1] = 0; it[2] = 1 }
        val paths = allocArray<CPointerVar<ByteVar>>(3).also { it[0] = "/dev/null".cstr.ptr; it[1] = null; it[2] = null }
        val fds = allocArray<IntVar>(3)
        val error = alloc<IntVar>()
        val pid = airlift_spawn(command.first(), strings(command), airlift_environ(), null, modes, paths, 0, fds, error.ptr)
        assertTrue(pid > 0, "spawn failed: ${strerror(error.value)?.toKString()}")
        val result = mutableListOf<Byte>()
        val buffer = ByteArray(4096)
        val ready = alloc<pollfd>()
        try {
            while (true) {
                val count = buffer.usePinned { read(fds[1], it.addressOf(0), buffer.size.convert()) }
                if (count > 0) { repeat(count.toInt()) { result += buffer[it] }; continue }
                if (count == 0L) break
                assertTrue(errno == EAGAIN || errno == EINTR)
                ready.fd = fds[1]; ready.events = POLLIN.toShort(); ready.revents = 0
                poll(ready.ptr, 1u, 2000)
            }
        } finally {
            close(fds[1])
            val status = alloc<IntVar>()
            while (waitpid(pid, status.ptr, 0) < 0 && errno == EINTR) {}
        }
        result.toByteArray().decodeToString()
    }

    @Test fun childStartsWithDefaultSigpipeAndEmptySignalMask() {
        val previous = signal(SIGPIPE, SIG_IGN)
        memScoped {
            val blocked = alloc<sigset_t>()
            val old = alloc<sigset_t>()
            sigemptyset(blocked.ptr); sigaddset(blocked.ptr, SIGUSR1)
            assertEquals(0, pthread_sigmask(SIG_BLOCK, blocked.ptr, old.ptr))
            try {
                val text = spawnHere(listOf("/bin/cat", "/proc/self/status"))
                assertEquals(0uL, status(text, "SigBlk"), text)
                assertEquals(0uL, status(text, "SigIgn") and (1uL shl (SIGPIPE - 1)), text)
            } finally {
                pthread_sigmask(SIG_SETMASK, old.ptr, null)
                signal(SIGPIPE, previous)
            }
        }
    }

    private suspend fun assertNotInherited() {
        val leaked = open("/dev/null", O_RDONLY) // deliberately without O_CLOEXEC, like stdio fopen()
        assertTrue(leaked >= 3)
        try {
            val text = output("/bin/sh", "-c", "if [ -e /proc/self/fd/$1 ]; then echo leaked; else echo closed; fi", "sh", leaked.toString())
            assertEquals("closed", text.trim())
            // Redirected standard descriptors still reach the child.
            assertEquals("visible", output("/bin/sh", "-c", "echo visible").trim())
        } finally { close(leaked) }
    }

    @Test fun descriptorsWithoutCloseOnExecAreNotInherited(): Unit = runBlocking { assertNotInherited() }

    @Test fun descriptorFallbackWithoutCloseRangeAlsoProtectsChildren(): Unit = runBlocking {
        airlift_set_close_range_state(-1) // as on kernels before 5.11 or under seccomp
        try { assertNotInherited() } finally { airlift_set_close_range_state(0) }
    }

    @Test fun parallelSpawnsKeepTheirOwnPipes(): Unit = runBlocking {
        val results = (1..48).map { index ->
            async(Dispatchers.Default) { output("/bin/sh", "-c", "printf '%s' \"$1\"", "sh", "child-$index") }
        }.awaitAll()
        assertEquals((1..48).map { "child-$it" }, results)
    }

    @Test fun waitingReaderResumesOnDataAndOnClose(): Unit = runBlocking {
        val child = launcher.start(ProcessSpec(listOf("/bin/sh", "-c", "sleep 0.2; printf ready; exec sleep 30")))
        try {
            child.stdin!!.close()
            val bytes = ByteArray(16)
            val count = withTimeout(5000) { child.stdout!!.read(bytes) }
            assertEquals("ready", bytes.copyOf(count).decodeToString())
            // A reader parked in the poller must not hang when its pipe is closed underneath it.
            val reading = async { runCatching { child.stdout!!.read(bytes) } }
            delay(50)
            child.stdout!!.close()
            val result = withTimeout(2000) { reading.await() }
            assertTrue(result.isFailure)
        } finally { child.close() }
    }

    @Test fun pollerReleaseWakesRegisteredWaiters(): Unit = runBlocking {
        memScoped {
            val fds = allocArray<IntVar>(2)
            assertEquals(0, airlift_pipe(fds))
            try {
                val registration = LinuxFdPoller.readable(fds[0])
                val waiting = async { registration.await() }
                delay(20)
                assertFalse(waiting.isCompleted)
                LinuxFdPoller.release(fds[0])
                withTimeout(2000) { waiting.await() }
                assertFalse(LinuxFdPoller.readable(fds[0]).await(50))
                airlift_wake(fds[1])
                assertTrue(LinuxFdPoller.readable(fds[0]).await(2000))
            } finally { close(fds[0]); close(fds[1]) }
        }
    }
}
