package iris.tools.airlift.monitor

import iris.tools.airlift.*
import iris.tools.airlift.process.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.io.files.Path
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SourceInventoryTest {
    private val config = AirliftConfig("user@host", "/data", Path("dest"))
    private class Bytes(private val data: ByteArray, private val fragment: Int = 1) : ProcessByteSource {
        private var offset = 0
        override suspend fun read(buffer: ByteArray): Int {
            if (offset == data.size) return -1
            val count = minOf(fragment, buffer.size, data.size - offset)
            data.copyInto(buffer, 0, offset, offset + count); offset += count
            return count
        }
        override suspend fun close() {}
    }
    private class Child(output: String, val code: Int = 0, error: String = "", block: Boolean = false) : ChildProcess {
        var closed = false
        var inputClosed = false
        override val stdin = object : ProcessByteSink {
            override suspend fun write(bytes: ByteArray) {}
            override suspend fun flush() {}
            override suspend fun close() { inputClosed = true }
        }
        override val stdout: ProcessByteSource = if (!block) Bytes(output.encodeToByteArray()) else object : ProcessByteSource {
            override suspend fun read(buffer: ByteArray): Int = awaitCancellation()
            override suspend fun close() {}
        }
        override val stderr = Bytes(error.encodeToByteArray(), 8192)
        override suspend fun exitCode(): Int = code
        override suspend fun awaitExit(): Int = code
        override suspend fun awaitExit(timeoutMillis: Long): Int = code
        override suspend fun terminate(force: Boolean) { closed = true }
        override suspend fun close() { closed = true }
    }

    @Test fun `remote discovery uses portable process with fragmented UTF8 and separate stderr`() = runTest {
        val child = Child("D\u0000f\t12\tпапка/имя\u0000", error = "diagnostic".repeat(10000))
        val result = readInventory(config, SourceQuery("/data/a'b"), ProcessLauncher { spec ->
            assertFalse(spec.mergeError)
            assertEquals(ProcessRedirect.Pipe, spec.error)
            assertContains(spec.command.last(), "/data/a\\047b")
            child
        })
        assertEquals(mapOf("папка/имя" to 12L), result.inventory.sizes)
        assertEquals(SourceMatch.LITERAL, result.match)
        assertTrue(child.closed && child.inputClosed)
    }

    @Test fun `extra SSH options follow Airlift options and precede the host`() = runTest {
        val custom = config.copy(sshOptions = "-o Compression=no -c aes128-gcm@openssh.com")
        readInventory(custom, SourceQuery("/data"), ProcessLauncher { spec ->
            val command = spec.command
            val host = command.indexOf("user@host")
            assertEquals(listOf("-o", "Compression=no", "-c", "aes128-gcm@openssh.com"), command.subList(host - 4, host))
            assertTrue(command.indexOf("StrictHostKeyChecking=yes") < host - 4)
            Child("D\u0000d\t0\t\u0000")
        })
    }

    @Test fun `failed SSH reports bounded diagnostic and closes process`() = runTest {
        val child = Child("", 255, "x".repeat(20000) + "Permission denied")
        val error = assertFailsWith<IllegalStateException> { readInventory(config, SourceQuery("/data"), ProcessLauncher { child }) }
        assertContains(error.message.orEmpty(), "Permission denied")
        assertContains(error.message.orEmpty(), "GNU find")
        assertTrue(error.message!!.length < 4400)
        assertTrue(child.closed)
    }

    @Test fun `missing remote source has a clear error`() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            readInventory(config, SourceQuery("/data/missing"), ProcessLauncher { Child("", 3, "Source does not exist") })
        }
        assertEquals("Source does not exist on user@host: /data/missing", error.message)
    }

    @Test fun `truncated record closes process`() = runTest {
        val child = Child("D\u0000f\t12\tunfinished")
        assertFailsWith<IllegalStateException> { readInventory(config, SourceQuery("/data"), ProcessLauncher { child }) }
        assertTrue(child.closed)
    }

    @Test fun `cancellation closes a process blocked reading inventory`() = runTest {
        val child = Child("", block = true)
        val job = launch { readInventory(config, SourceQuery("/data"), ProcessLauncher { child }) }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(child.closed)
    }

    @Test fun `cancellation during startup handover still closes the created process`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val child = Child("D\u0000")
        val job = launch { readInventory(config, SourceQuery("/data"), ProcessLauncher {
            entered.complete(Unit); release.await(); child
        }) }
        entered.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertTrue(child.closed)
    }

    @Test fun `SSH check captures merged bounded diagnostics and closes process`() = runTest {
        val child = Child("x".repeat(20000) + "Permission denied", 255)
        val result = runSshCheck(listOf("ssh", "user@host", "true"), false, ProcessLauncher { spec ->
            assertTrue(spec.mergeError)
            assertEquals("C", spec.environment["LC_ALL"])
            assertEquals(ProcessRedirect.Pipe, spec.output)
            child
        })
        assertEquals(255, result.exitCode)
        assertEquals(8192, result.output.length)
        assertTrue(result.output.endsWith("Permission denied"))
        assertTrue(child.closed && child.inputClosed)
    }

    @Test fun `interactive trust check bypasses cached password and inherits terminal`() = runTest {
        val child = Child("", 255)
        SshAuthentication.usePassword("test-password".toCharArray())
        try {
            val result = runSshCheck(listOf("ssh", "user@host", "true"), true, ProcessLauncher { spec ->
                assertEquals("ssh", spec.command.first())
                assertTrue("SSHPASS" in spec.environment)
                assertNull(spec.environment["SSHPASS"])
                assertEquals(ProcessRedirect.Inherit, spec.input)
                assertEquals(ProcessRedirect.Inherit, spec.output)
                assertEquals(ProcessRedirect.Inherit, spec.error)
                assertFalse(spec.mergeError)
                child
            })
            assertEquals("", result.output)
            assertFalse(child.inputClosed)
            assertTrue(child.closed)
        } finally { SshAuthentication.clearPassword() }
    }

    @Test fun `cancelling SSH check closes process while reading diagnostics`() = runTest {
        val child = Child("", block = true)
        val job = launch { runSshCheck(listOf("ssh", "user@host"), false, ProcessLauncher { child }) }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(child.closed)
    }
}
