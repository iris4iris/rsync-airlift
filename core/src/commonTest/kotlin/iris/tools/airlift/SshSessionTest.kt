package iris.tools.airlift

import iris.tools.airlift.process.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.files.Path
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SshSessionTest {
    private class Sink : ProcessByteSink {
        val data = Buffer()
        var closed = false
        override suspend fun write(bytes: ByteArray) { data.write(bytes) }
        override suspend fun flush() {}
        override suspend fun close() { closed = true }
    }
    private class Files : SshFiles {
        val logs = mutableMapOf<String, Sink>()
        var deleted = false
        val created = mutableListOf<String>()
        override suspend fun temporaryDirectory(prefix: String) = Path("/tmp/airlift-test")
        override suspend fun createDirectories(path: Path) { created.add(path.toString()) }
        override suspend fun readTail(path: Path, maxCharacters: Int) = "master denied"
        override suspend fun log(path: Path) = Sink().also { logs[path.toString()] = it }
        override suspend fun deleteOwnedDirectory(path: Path) { deleted = true }
    }
    private class Child(private val code: Int?, text: String = "", private val block: Boolean = false) : ChildProcess {
        override val stdin = Sink()
        var closed = false
        var position = 0
        val bytes = text.encodeToByteArray()
        private val ended = CompletableDeferred<Unit>()
        override val stdout = object : ProcessByteSource {
            override suspend fun read(buffer: ByteArray): Int {
                if (block) { ended.await(); return -1 }
                if (position == bytes.size) return -1
                buffer[0] = bytes[position++] // Exercise split UTF-8 sequences and CR/LF boundaries.
                return 1
            }
            override suspend fun close() {}
        }
        override val stderr: ProcessByteSource? = null
        override suspend fun exitCode() = if (closed) -1 else code
        override suspend fun awaitExit() = requireNotNull(exitCode())
        override suspend fun awaitExit(timeoutMillis: Long) = exitCode()
        override suspend fun terminate(force: Boolean) { closed = true; ended.complete(Unit) }
        override suspend fun close() { closed = true; ended.complete(Unit) }
    }
    private fun factory(files: Files, launcher: ProcessLauncher,
        config: AirliftConfig = AirliftConfig("user@host", "/source", Path("/destination"))) = SshSessionFactory(
        config, launcher, files,
        ProcessAuthentication { it }, object : DispatcherPaths { override fun absolute(path: Path) = path },
        object : DispatcherClock { override fun wallTimeMillis() = 0L; override fun monotonicNanos() = 0L },
    )

    @Test fun `shared SSH reuses a master and streams NUL lists logs and real progress`() = runTest {
        val files = Files()
        val specs = mutableListOf<ProcessSpec>()
        val children = mutableListOf<Child>()
        val output = "AIRLIFT_FILE:папка/a.bin\r5 50% 1.00MB/s 0:00:01\rLiteral data: 4 bytes\nMatched data: 6 bytes\n"
        val session = factory(files, ProcessLauncher { spec ->
            specs.add(spec)
            Child(if ("-M" in spec.command) null else 0, if (spec.command.first() == "rsync") output else "").also { children.add(it) }
        }).create(1)
        try {
            session.connect()
            val progress = mutableListOf<TransferProgress>()
            val task = TransferTask("папка/a.bin", 10, files = listOf("папка/a.bin", "b"), entries = listOf("."))
            assertEquals(0, session.transfer(task, Path("batch.log")) { progress.add(it) })
            assertEquals(0, session.transfer(TransferTask("b", 10), Path("single.log")) {})
            assertEquals(1, specs.count { "-M" in it.command })
            assertEquals(2, specs.count { it.command.first() == "rsync" })
            val rsync = specs.filter { it.command.first() == "rsync" }
            assertContains(rsync.first().command, "--recursive")
            assertContains(rsync.first().command, "--files-from=-")
            assertTrue(rsync.first().command.any { "ControlMaster=no" in it && "ProxyCommand=false" in it })
            assertEquals(".\u0000", children[2].stdin.data.readByteArray().decodeToString())
            assertEquals("b\u0000", children[3].stdin.data.readByteArray().decodeToString())
            assertEquals(output, files.logs.getValue("batch.log").data.readByteArray().decodeToString())
            assertEquals("папка/a.bin", progress.last().currentFile)
            assertEquals(6, progress.last().matchedBytes)
            assertEquals(4, progress.last().literalBytes)
            assertTrue(specs.all { it.environment["LC_ALL"] == "C" })
        } finally { session.close() }
        assertTrue(files.deleted)
        assertTrue(children.all { it.closed })
    }

    @Test fun `failed transfer reports original split output and clears it on next attempt`() = runTest {
        val files = Files()
        var attempt = 0
        val session = factory(files, ProcessLauncher { spec ->
            if (spec.command.first() == "rsync") {
                attempt++
                if (attempt == 1) Child(127, "bash: rsync: command not found\r\nrsync error: remote command not found (code 127)")
                else Child(0, "AIRLIFT_FILE:a\n")
            } else Child(if ("-M" in spec.command) null else 0)
        }).create(1)
        try {
            session.connect()
            assertEquals(127, session.transfer(TransferTask("a"), Path("failed.log")) {})
            assertEquals("bash: rsync: command not found", session.failureDetails)
            assertEquals(0, session.transfer(TransferTask("a"), Path("retry.log")) {})
            assertNull(session.failureDetails)
        } finally { session.close() }
    }

    @Test fun `cancelling transfer closes the child and log without a JVM process`() = runTest {
        val files = Files()
        val children = mutableListOf<Child>()
        val session = factory(files, ProcessLauncher { spec ->
            Child(if ("-M" in spec.command) null else 0, block = spec.command.first() == "rsync").also { children.add(it) }
        }).create(1)
        try {
            session.connect()
            val transfer = launch { session.transfer(TransferTask("a"), Path("cancel.log")) {} }
            runCurrent()
            transfer.cancelAndJoin()
            assertTrue(children.last().closed)
            assertTrue(files.logs.getValue("cancel.log").closed)
        } finally { session.close() }
        assertTrue(files.deleted)
    }

    @Test fun `failed master reports diagnostics and cleans the temporary directory`() = runTest {
        val files = Files()
        val child = Child(255)
        val session = factory(files, ProcessLauncher { child }).create(1)
        val error = assertFailsWith<IllegalStateException> { session.connect() }
        assertContains(error.message.orEmpty(), "master denied")
        assertTrue(child.closed)
        assertTrue(files.deleted)
        session.close()
    }

    @Test fun `connection check asks the master and never reconnects`() = runTest {
        val files = Files()
        var probeCode = 0
        var master: Child? = null
        val specs = mutableListOf<ProcessSpec>()
        val session = factory(files, ProcessLauncher { spec ->
            specs.add(spec)
            if ("-M" in spec.command) Child(null).also { master = it } else Child(probeCode)
        }).create(1)
        try {
            session.connect()
            assertTrue(session.isConnected())
            assertTrue(specs.last().command.containsAll(listOf("-O", "check")))
            probeCode = 255
            assertFalse(session.isConnected())
            probeCode = 0
            master!!.terminate()
            assertFalse(session.isConnected())
            assertEquals(1, specs.count { "-M" in it.command })
        } finally { session.close() }
    }

    @Test fun `user ssh options follow Airlift options and upload does not create local directories`() = runTest {
        val files = Files()
        val specs = mutableListOf<ProcessSpec>()
        val config = AirliftConfig("user@host", "/destination", Path("/source/a.bin"), direction = TransferDirection.UPLOAD,
            sshOptions = "-c aes128-gcm@openssh.com -o ProxyCommand=none")
        val session = factory(files, ProcessLauncher { spec ->
            specs.add(spec)
            Child(if ("-M" in spec.command) null else 0)
        }, config).create(1)
        try {
            session.connect()
            assertEquals(0, session.transfer(TransferTask("b.bin", 1, sourceRoot = "/other"), Path("upload.log")) {})
            assertTrue(files.created.isEmpty())
            val master = specs.first { "-M" in it.command }.command
            assertTrue(master.indexOf("aes128-gcm@openssh.com") > master.indexOf("ServerAliveCountMax=3"))
            assertEquals("user@host", master.last())
            val transport = specs.first { it.command.first() == "rsync" }.command.let { it[it.indexOf("-e") + 1] }
            assertTrue(transport.indexOf("ProxyCommand=false") < transport.indexOf("ProxyCommand=none"))
            assertContains(transport, "'-c' 'aes128-gcm@openssh.com'")
        } finally { session.close() }
    }

    @Test fun `download creates the local destination before rsync`() = runTest {
        val files = Files()
        val session = factory(files, ProcessLauncher { spec -> Child(if ("-M" in spec.command) null else 0) }).create(1)
        try {
            session.connect()
            session.transfer(TransferTask("a"), Path("download.log")) {}
            assertEquals(listOf(Path("/destination").toString()), files.created)
        } finally { session.close() }
    }

    @Test fun `closing while a process starts waits for registration and leaves no orphan`() = runTest {
        val files = Files()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val child = Child(null)
        val session = factory(files, ProcessLauncher {
            entered.complete(Unit); release.await(); child
        }).create(1)
        val connection = async { runCatching { session.connect() } }
        entered.await()
        val closing = async { session.close() }
        runCurrent()
        assertFalse(files.deleted)
        release.complete(Unit)
        closing.await()
        assertTrue(connection.await().isFailure)
        assertTrue(child.closed)
        assertTrue(files.deleted)
    }
}
