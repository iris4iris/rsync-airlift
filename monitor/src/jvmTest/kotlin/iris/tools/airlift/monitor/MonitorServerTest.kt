package iris.tools.airlift.monitor

import iris.tools.airlift.*
import kotlinx.coroutines.runBlocking
import java.io.DataOutputStream
import java.net.Socket
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import kotlin.test.*

class MonitorServerTest {
    private class Source : MonitorSource {
        val requested = mutableListOf<Int>()
        override suspend fun snapshot() = AirliftSnapshot(emptyList(), emptyList(), false, false, 3)
        override suspend fun pause() {}
        override suspend fun resume() {}
        override suspend fun setWorkerCount(count: Int) { requested += count }
    }

    private fun <T> withServer(limits: MonitorServerLimits = MonitorServerLimits(), transport: MonitorTransport = monitorTransport(),
        source: MonitorSource = Source(), block: (endpoint: java.nio.file.Path, port: Int) -> T): T {
        val directory = Files.createTempDirectory("airlift-server-")
        val endpoint = directory.resolve("connection")
        try {
            return MonitorServer(source, endpoint.toPortablePath(), limits, transport).use {
                val port = Properties().apply { Files.newInputStream(endpoint).use { load(it) } }.getProperty("port").toInt()
                block(endpoint, port)
            }
        } finally { Files.deleteIfExists(endpoint); Files.deleteIfExists(directory) }
    }

    /** Milliseconds until the server closes [socket], or null if it stays open for [limit]. */
    private fun closedWithin(socket: Socket, limit: Int): Long? {
        socket.soTimeout = limit
        val started = System.nanoTime()
        return try { if (socket.getInputStream().read() == -1) (System.nanoTime() - started) / 1_000_000 else null }
        catch (_: java.net.SocketTimeoutException) { null }
        catch (_: java.net.SocketException) { (System.nanoTime() - started) / 1_000_000 }
    }

    @Test fun `silent and trickling clients are dropped at the request deadline`() =
        withServer(MonitorServerLimits(requestMillis = 400)) { _, port ->
            Socket("127.0.0.1", port).use { silent ->
                assertNotNull(closedWithin(silent, 3000), "a silent client must not hold a connection slot")
            }
            Socket("127.0.0.1", port).use { trickle ->
                val output = trickle.getOutputStream()
                output.write(byteArrayOf(0, 0, 0, 4, 0, 64)); output.flush() // version and a maximal token length
                var closed = false
                repeat(20) {
                    if (closed) return@repeat
                    try { output.write('a'.code); output.flush() } catch (_: java.io.IOException) { closed = true }
                    Thread.sleep(100)
                }
                assertTrue(closed || closedWithin(trickle, 1000) != null, "per-byte progress must not extend the deadline")
            }
        }

    @Test fun `declared oversized token is rejected before its body is read`() = withServer { _, port ->
        Socket("127.0.0.1", port).use { socket ->
            DataOutputStream(socket.getOutputStream()).apply { writeInt(PROTOCOL); writeShort(65535); flush() }
            assertNotNull(closedWithin(socket, 2000))
        }
    }

    @Test fun `connections above the limit are closed while others are served`() =
        withServer(MonitorServerLimits(connections = 2, requestMillis = 1500)) { endpoint, port ->
            val idle = List(2) { Socket("127.0.0.1", port) }
            try {
                Thread.sleep(200)
                Socket("127.0.0.1", port).use { rejected -> assertNotNull(closedWithin(rejected, 1000)) }
            } finally { idle.forEach { it.close() } }
            Thread.sleep(200)
            assertEquals(3, runBlocking { RemoteMonitorSource(endpoint.toPortablePath()).snapshot() }.targetWorkers)
        }

    @Test fun `accept loop survives transient accept failures`() {
        val real = monitorTransport()
        var failures = 0
        val flaky = object : MonitorTransport {
            override fun listen(): MonitorListener {
                val listener = real.listen()
                return object : MonitorListener by listener {
                    override suspend fun accept(): MonitorSocket {
                        if (failures < 3) { failures++; throw java.io.IOException("Too many open files") }
                        return listener.accept()
                    }
                }
            }
            override suspend fun connect(port: Int) = real.connect(port)
        }
        withServer(MonitorServerLimits(acceptRetryMillis = 50), flaky) { endpoint, _ ->
            assertEquals(3, runBlocking { RemoteMonitorSource(endpoint.toPortablePath()).snapshot() }.targetWorkers)
            assertEquals(3, failures)
        }
    }

    @Test fun `worker command is validated by the server`() {
        val source = Source()
        withServer(source = source) { endpoint, _ ->
            val client = RemoteMonitorSource(endpoint.toPortablePath())
            for (count in listOf(0, -1, AirliftConfig.MAX_WORKERS + 1, Int.MAX_VALUE)) {
                val error = assertFailsWith<IllegalStateException> { runBlocking { client.setWorkerCount(count) } }
                assertContains(error.message!!, "between 1 and ${AirliftConfig.MAX_WORKERS}")
            }
            runBlocking { client.setWorkerCount(AirliftConfig.MAX_WORKERS) }
            assertEquals(listOf(AirliftConfig.MAX_WORKERS), source.requested)
        }
    }

    @Test fun `connection file is private and created through one exclusive handle`() = withServer { endpoint, _ ->
        val properties = Properties().apply { Files.newInputStream(endpoint).use { load(it) } }
        assertEquals(64, properties.getProperty("token").length)
        val views = endpoint.fileSystem.supportedFileAttributeViews()
        if ("posix" in views) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(endpoint, LinkOption.NOFOLLOW_LINKS))
        } else if ("acl" in views) {
            val view = Files.getFileAttributeView(endpoint, AclFileAttributeView::class.java)
            val owner = view.owner.name
            val user = System.getProperty("user.name")
            assertTrue(view.acl.isNotEmpty())
            assertTrue(view.acl.all { it.principal().name == owner || it.principal().name.substringAfterLast('\\') == user },
                "unexpected ACL entries: ${view.acl.map { it.principal().name }}")
        }
    }

    @Test fun `existing link at the endpoint is never followed`() {
        val directory = Files.createTempDirectory("airlift-link-")
        val target = directory.resolve("target")
        val endpoint = directory.resolve("connection")
        try {
            Files.writeString(target, "original")
            try { Files.createSymbolicLink(endpoint, target) } catch (_: Exception) { return } // No symlink privilege.
            assertFailsWith<IllegalStateException> { MonitorServer(Source(), endpoint.toPortablePath()) }
            assertEquals("original", Files.readString(target))
        } finally {
            Files.deleteIfExists(endpoint); Files.deleteIfExists(target); Files.deleteIfExists(directory)
        }
    }
}
