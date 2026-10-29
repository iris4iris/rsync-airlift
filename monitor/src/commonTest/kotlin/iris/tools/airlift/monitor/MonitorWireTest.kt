package iris.tools.airlift.monitor

import iris.tools.airlift.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.*

class MonitorWireTest {
    private class MemorySocket : MonitorSocket {
        val data = Buffer()
        override suspend fun read(bytes: ByteArray): Int {
            if (data.size == 0L) return -1
            // Exercise every primitive/string across fragmented transport reads.
            bytes[0] = data.readByte()
            return 1
        }
        override suspend fun write(bytes: ByteArray) { data.write(bytes) }
        override suspend fun flush() {}
        override fun close() {}
    }

    @Test fun `protocol three modified UTF preserves NUL and surrogate pairs`() = runTest {
        val socket = MemorySocket()
        val output = MonitorOutput(socket)
        output.writeUTF("A\u0000Ж😀")
        output.flush()
        val expected = byteArrayOf(0, 11, 65, -64, -128, -48, -106, -19, -96, -67, -19, -72, -128)
        val actual = socket.data.copy().readByteArray()
        assertContentEquals(expected, actual)
        assertEquals("A\u0000Ж😀", MonitorInput(socket).readUTF())
    }

    @Test fun `snapshot roundtrip retains nullable fields statistics and batch totals without file lists`() = runTest {
        val socket = MemorySocket()
        val task = TaskState(TransferTask("папка/a😀", 42, files = listOf("папка/a😀", "b")),
            TaskStatus.SUCCEEDED, 2, TransferProgress(42, 12.5, 0, 100, 30, 12, "b", 2, currentFileIndex = 2), "message",
            warning = "Some source files vanished before they were copied (code 24).")
        val renamed = TaskState(TransferTask("plain\tname=x\n", null, id = "batch-7", fileTotal = 2_000_000), TaskStatus.QUEUED)
        val snapshot = AirliftSnapshot(listOf(task, renamed), listOf(WorkerState(7, WorkerStatus.CONNECTING, task.task.id,
            "SSH connection lost; reconnecting (2/3)")), false, false, 8, 10, 20)
        val output = MonitorOutput(socket)
        Wire.write(output, snapshot)
        output.flush()
        val decoded = Wire.read(MonitorInput(socket))
        val expected = snapshot.copy(tasks = listOf(task.copy(task = TransferTask("папка/a😀", 42, fileTotal = 2)), renamed))
        assertEquals(expected, decoded)
        assertEquals(2, decoded.tasks[0].task.fileCount)
        assertTrue(decoded.tasks[0].task.isBatch)
        assertEquals(2_000_000, decoded.tasks[1].task.fileCount)
        assertEquals("batch-7", decoded.tasks[1].task.id)
    }

    @Test fun `task id is omitted on the wire when it equals the path`() = runTest {
        suspend fun size(task: TransferTask): Long {
            val socket = MemorySocket()
            val output = MonitorOutput(socket)
            Wire.write(output, AirliftSnapshot(listOf(TaskState(task)), emptyList(), false, false)); output.flush()
            return socket.data.size
        }
        assertEquals(size(TransferTask("a/b.bin")) + 4 + "other".length, size(TransferTask("a/b.bin", id = "other")))
    }

    @Test fun `oversized lists and handshake fields are rejected before allocation`() = runTest {
        val socket = MemorySocket()
        val output = MonitorOutput(socket)
        output.writeBoolean(false); output.writeBoolean(false); output.writeInt(1)
        output.writeBoolean(false); output.writeBoolean(false)
        output.writeInt(Wire.MAX_COUNT + 1)
        output.flush()
        assertFailsWith<IllegalArgumentException> { Wire.read(MonitorInput(socket)) }
        val handshake = MemorySocket()
        MonitorOutput(handshake).apply { writeUTF("x".repeat(65)); flush() }
        assertFailsWith<IllegalArgumentException> { MonitorInput(handshake).readUTFBytes(64) }
        assertEquals(65L, handshake.data.size) // The declared body was never read.
    }

    @Test fun `token comparison is exact`() {
        val token = "0123456789abcdef".encodeToByteArray()
        assertTrue(constantTimeEquals(token, token.copyOf()))
        assertFalse(constantTimeEquals(token, "0123456789abcdeF".encodeToByteArray()))
        assertFalse(constantTimeEquals(token, token.copyOf(15)))
        assertFalse(constantTimeEquals(token, token + 0.toByte()))
        assertFalse(constantTimeEquals(token, ByteArray(0)))
    }

    @Test fun `bulk reads copy whole chunks across transport boundaries`() = runTest {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        val socket = object : MonitorSocket {
            var offset = 0
            var reads = 0
            override suspend fun read(bytes: ByteArray): Int {
                if (offset == payload.size) return -1
                val count = minOf(bytes.size, 7_000, payload.size - offset)
                payload.copyInto(bytes, 0, offset, offset + count); offset += count; reads++
                return count
            }
            override suspend fun write(bytes: ByteArray) {}
            override suspend fun flush() {}
            override fun close() {}
        }
        val input = MonitorInput(socket)
        val head = input.readInt()
        val rest = ByteArray(payload.size - 4).also { input.readFully(it) }
        assertEquals(((0 shl 24) or (1 shl 16) or (2 shl 8) or 3), head)
        assertContentEquals(payload.copyOfRange(4, payload.size), rest)
        assertEquals((payload.size + 6_999) / 7_000, socket.reads)
    }

    @Test fun `invalid text length is rejected before allocating payload`() = runTest {
        val socket = MemorySocket()
        val output = MonitorOutput(socket)
        output.writeInt(1_048_577)
        output.flush()
        assertFailsWith<IllegalArgumentException> { Wire.text(MonitorInput(socket)) }
    }
}
