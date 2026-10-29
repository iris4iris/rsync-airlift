package iris.tools.airlift.monitor

import iris.tools.airlift.AirliftSnapshot
import kotlinx.coroutines.*
import kotlinx.io.files.Path
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Properties
import kotlin.test.*

class MonitorTransportTest {
    @Test fun `legacy data stream client interoperates with common server`(): Unit = runBlocking {
        val directory = Files.createTempDirectory("airlift-wire-")
        val endpoint = directory.resolve("connection")
        val source = object : MonitorSource {
            override suspend fun snapshot() = AirliftSnapshot(emptyList(), emptyList(), false, false, 7)
            override suspend fun pause() {}
            override suspend fun resume() {}
            override suspend fun setWorkerCount(count: Int) {}
        }
        try {
            MonitorServer(source, Path(endpoint.toString())).use {
                val properties = Properties().apply { Files.newInputStream(endpoint).use { load(it) } }
                fun request(version: Int, token: String, verify: (DataInputStream) -> Unit) {
                    Socket("127.0.0.1", properties.getProperty("port").toInt()).use { socket ->
                        socket.soTimeout = 3000
                        val output = DataOutputStream(socket.getOutputStream())
                        output.writeInt(version); output.writeUTF(token); output.writeUTF("snapshot"); output.flush()
                        verify(DataInputStream(socket.getInputStream()))
                    }
                }
                assertEquals("4", properties.getProperty("protocol"))
                request(4, properties.getProperty("token")) { input ->
                    assertTrue(input.readBoolean())
                    assertFalse(input.readBoolean()); assertFalse(input.readBoolean())
                    assertEquals(7, input.readInt())
                    assertFalse(input.readBoolean()); assertFalse(input.readBoolean())
                    assertEquals(0, input.readInt()); assertEquals(0, input.readInt())
                }
                // A protocol-3 client receives a readable explanation instead of undecodable data.
                request(3, properties.getProperty("token")) { input ->
                    assertFalse(input.readBoolean())
                    val size = input.readInt()
                    assertContains(String(input.readNBytes(size), Charsets.UTF_8), "protocol changed")
                }
                request(4, "wrong-token") { assertEquals(-1, it.read()) }
            }
        } finally { Files.deleteIfExists(endpoint); Files.delete(directory) }
    }

    @Test fun `cancelling a socket read closes connection and releases IO worker`(): Unit = runBlocking {
        ServerSocket(0).use { server ->
            val socket = monitorTransport().connect(server.localPort)
            server.accept().use { peer ->
                peer.soTimeout = 3000
                val reading = launch { socket.read(ByteArray(8)) }
                yield()
                reading.cancelAndJoin()
                socket.close()
                assertEquals(-1, peer.getInputStream().read())
            }
        }
    }

    @Test fun `cancelling accept releases listener`(): Unit = runBlocking {
        val listener = monitorTransport().listen()
        try {
            val accepting = launch { listener.accept().close() }
            yield()
            accepting.cancelAndJoin()
        } finally { listener.close() }
    }
}
