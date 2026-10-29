@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package iris.tools.airlift.monitor

import iris.tools.airlift.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.*
import kotlin.test.*

class LinuxMonitorTest {
    private class Source : MonitorSource {
        override val from = "user@host:/папка/😀"
        override val to = "/target with space"
        var value = AirliftSnapshot(emptyList(), emptyList(), false, false, 4)
        override suspend fun snapshot() = value
        override suspend fun pause() { value = value.copy(paused = true) }
        override suspend fun resume() { value = value.copy(paused = false) }
        override suspend fun setWorkerCount(count: Int) { value = value.copy(targetWorkers = count) }
    }

    @Test fun loopbackServerClientAndProtectedEndpoint(): Unit = runBlocking {
        val directory = monitorApplicationPlatform().temporaryDirectory("airlift-monitor-test-")
        val endpoint = Path(directory, "connection")
        val source = Source()
        var server: MonitorServer? = null
        try {
            server = MonitorServer(source, endpoint)
            memScoped {
                val info = alloc<stat>()
                assertEquals(0, stat(endpoint.toString(), info.ptr))
                assertEquals(384u, info.st_mode and 511u)
            }
            val original = readConnectionFile(endpoint)
            assertTrue(original.getValue("token").length >= 32)
            assertFailsWith<IllegalStateException> { MonitorServer(source, endpoint) }
            assertEquals(original, readConnectionFile(endpoint))
            val client = RemoteMonitorSource(endpoint)
            assertEquals(source.from, client.from)
            assertEquals(source.to, client.to)
            assertEquals(4, client.snapshot().targetWorkers)
            client.pause(); assertTrue(client.snapshot().paused)
            client.resume(); assertFalse(client.snapshot().paused)
            client.setWorkerCount(7); assertEquals(7, client.snapshot().targetWorkers)
            server.close(); server.close()
            assertFalse(SystemFileSystem.exists(endpoint))
        } finally {
            server?.close()
            SystemFileSystem.delete(directory)
        }
    }

    @Test fun cancellationClosesSocketAndListener(): Unit = runBlocking {
        val listener = monitorTransport().listen()
        val client = monitorTransport().connect(listener.port)
        val peer = listener.accept()
        try {
            val reading = launch { client.read(ByteArray(32)) }
            delay(20); reading.cancelAndJoin()
            assertEquals(-1, withTimeout(2000) { peer.read(ByteArray(32)) })
            val accepting = launch { listener.accept().close() }
            delay(20); accepting.cancelAndJoin()
        } finally { client.close(); peer.close(); listener.close() }
    }

    @Test fun localInventoryDoesNotFollowDirectoryLinks(): Unit = runBlocking {
        val directory = monitorApplicationPlatform().temporaryDirectory("airlift-inventory-test-")
        val outside = monitorApplicationPlatform().temporaryDirectory("airlift-outside-test-")
        val link = Path(directory, "link")
        try {
            assertEquals(0, symlink(outside.toString(), link.toString()))
            val inventory = readLocalInventory(directory.toString())
            assertEquals(setOf("link"), inventory.sizes.keys)
            assertEquals(setOf(""), inventory.directories)
            assertTrue(readLocalInventory(link.toString()).singleFile)
        } finally {
            unlink(link.toString()); SystemFileSystem.delete(directory); SystemFileSystem.delete(outside)
        }
    }

    @Test fun acceptSurvivesDescriptorExhaustion(): Unit = runBlocking {
        assertEquals(listOf(50L, 100L, 200L, 400L, 800L, 1000L, 1000L),
            generateSequence(acceptBackoffMillis(0)) { acceptBackoffMillis(it) }.take(7).toList())
        val listener = monitorTransport().listen()
        val client = monitorTransport().connect(listener.port) // queued in the listen backlog
        memScoped {
            val limit = alloc<platform.linux.rlimit>()
            assertEquals(0, platform.linux.getrlimit(platform.linux.RLIMIT_NOFILE, limit.ptr))
            val original = limit.rlim_cur
            val lowestFree = open("/dev/null", O_RDONLY or O_CLOEXEC)
            close(lowestFree)
            try {
                limit.rlim_cur = lowestFree.convert() // every new descriptor now fails with EMFILE
                assertEquals(0, platform.linux.setrlimit(platform.linux.RLIMIT_NOFILE, limit.ptr))
                val accepting = async { listener.accept() }
                delay(300)
                assertTrue(accepting.isActive, "EMFILE must be retried, not end the accept loop")
                limit.rlim_cur = original
                assertEquals(0, platform.linux.setrlimit(platform.linux.RLIMIT_NOFILE, limit.ptr))
                withTimeout(5000) { accepting.await() }.close()
            } finally {
                limit.rlim_cur = original
                platform.linux.setrlimit(platform.linux.RLIMIT_NOFILE, limit.ptr)
                client.close(); listener.close()
            }
        }
    }

    @Test fun idleAcceptAndReadWakeOnlyForEvents(): Unit = runBlocking {
        val listener = monitorTransport().listen()
        try {
            val accepting = async { listener.accept() }
            delay(100)
            val client = monitorTransport().connect(listener.port)
            val peer = withTimeout(2000) { accepting.await() }
            try {
                val reading = async { ByteArray(4).let { bytes -> peer.read(bytes).let { bytes.copyOf(it).decodeToString() } } }
                delay(100)
                assertTrue(reading.isActive)
                client.write("ping".encodeToByteArray())
                assertEquals("ping", withTimeout(2000) { reading.await() })
            } finally { client.close(); peer.close() }
            // Closing the listener wakes an accept parked in the poller.
            val parked = async { runCatching { listener.accept() } }
            delay(50)
            listener.close()
            assertTrue(withTimeout(2000) { parked.await() }.isFailure)
        } finally { listener.close() }
    }

    @Test fun hangupSchedulesShutdownUnlessIgnoredAtLaunch(): Unit = runBlocking {
        if (iris.tools.airlift.monitor.posix.airlift_signal_ignored(SIGHUP) != 0) return@runBlocking // started under nohup
        val called = CompletableDeferred<Unit>()
        val registration = monitorApplicationPlatform().onShutdown { called.complete(Unit) }
        try {
            assertEquals(0, kill(getpid(), SIGHUP))
            withTimeout(2000) { called.await() }
        } finally { registration.remove() }
    }

    @Test fun ignoredHangupStaysIgnoredLikeNohup() {
        val previous = signal(SIGHUP, SIG_IGN)
        try {
            val registration = monitorApplicationPlatform().onShutdown { }
            try { assertEquals(1, iris.tools.airlift.monitor.posix.airlift_signal_ignored(SIGHUP)) }
            finally { registration.remove() }
            assertEquals(1, iris.tools.airlift.monitor.posix.airlift_signal_ignored(SIGHUP))
        } finally { signal(SIGHUP, previous) }
    }

    @Test fun termSignalSchedulesCoroutineShutdown(): Unit = runBlocking {
        val called = CompletableDeferred<Unit>()
        val registration = monitorApplicationPlatform().onShutdown { called.complete(Unit) }
        try {
            assertEquals(0, kill(getpid(), SIGTERM))
            withTimeout(2000) { called.await() }
        } finally { registration.remove() }
    }
}
