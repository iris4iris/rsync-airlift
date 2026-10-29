package iris.tools.airlift.monitor

import iris.tools.airlift.*
import com.googlecode.lanterna.TextColor
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class BatchExecutionTest {
    @Test fun `one batch invokes one session transfer counts files and includes skipped bytes in matched`(): Unit = kotlinx.coroutines.runBlocking {
        val root = Files.createTempDirectory("airlift-batch-")
        try {
            val task = TransferTask("a", 1000, files = listOf("a", "b", "c"))
            var calls = 0
            val factory = TransferSessionFactory {
                object : TransferSession {
                    override suspend fun connect() {}
                    override suspend fun close() {}
                    override suspend fun transfer(task: TransferTask, logFile: kotlinx.io.files.Path, progress: suspend (TransferProgress) -> Unit): Int {
                        calls++
                        assertEquals(listOf("a", "b", "c"), task.files)
                        progress(TransferProgress(500, 100.0, 0, matchedBytes = 400, literalBytes = 100, currentFile = "b", transferredFiles = 1))
                        return 0
                    }
                }
            }
            AirliftDispatcher(AirliftConfig("user@host", "/source", root.toPortablePath(), workers = 1, logDirectory = root.resolve("logs").toPortablePath()), listOf(task), factory).use { dispatcher ->
                val final = run { dispatcher.start(); kotlinx.coroutines.withTimeout(5000) { dispatcher.awaitCompletion() } }
                assertEquals(1, calls)
                assertEquals(3, final.completed)
                assertEquals(900L, final.tasks.single().progress?.matchedBytes)
                assertEquals(100L, final.tasks.single().progress?.literalBytes)
                val parts = WorkerLineLayout(88).segments(final.workers.single(), final.tasks.single())
                assertTrue(parts.any { it.color == MonitorColor.ANSI.CYAN && it.text.contains('|') })
                assertTrue(parts.any { it.color == MonitorColor.ANSI.GREEN && it.text.contains('|') })
                val endpoint = root.resolve("connection")
                val source = object : MonitorSource {
                    override suspend fun snapshot() = final
                    override suspend fun pause() {}
                    override suspend fun resume() {}
                    override suspend fun setWorkerCount(count: Int) {}
                }
                MonitorServer(source, endpoint.toPortablePath()).use {
                    val remote = RemoteMonitorSource(endpoint.toPortablePath()).snapshot()
                    assertEquals(3, remote.completed)
                    // Protocol 4 sends the batch size, never the file list.
                    assertEquals(3, remote.tasks.single().task.fileTotal)
                    assertTrue(remote.tasks.single().task.files.isEmpty())
                    assertTrue(remote.tasks.single().task.isBatch)
                    assertEquals("b", remote.tasks.single().progress?.currentFile)
                    assertEquals(1, remote.tasks.single().progress?.transferredFiles)
                }
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
