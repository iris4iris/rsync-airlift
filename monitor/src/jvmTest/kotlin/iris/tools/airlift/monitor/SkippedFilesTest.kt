package iris.tools.airlift.monitor

import iris.tools.airlift.*
import com.googlecode.lanterna.TextColor
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SkippedFilesTest {
    @Test fun `successful rsync with zero statistics finishes full file progress and counts as skipped`(): Unit = kotlinx.coroutines.runBlocking {
        val root = Files.createTempDirectory("airlift-skipped-")
        try {
            val sessions = TransferSessionFactory {
                object : TransferSession {
                    override suspend fun connect() {}
                    override suspend fun close() {}
                    override suspend fun transfer(task: TransferTask, logFile: kotlinx.io.files.Path, progress: suspend (TransferProgress) -> Unit): Int {
                        val parser = RsyncOutputParser()
                        // Typical --stats for an unchanged file: no progress records.
                        listOf("Literal data: 0 bytes", "Matched data: 0 bytes").forEach { parser.parse(it)?.let { progress(it) } }
                        return 0
                    }
                }
            }
            AirliftDispatcher(AirliftConfig("user@host", "/source", root.toPortablePath(), workers = 1, logDirectory = root.resolve("logs").toPortablePath()),
                listOf(TransferTask("unchanged", 1024)), sessions).use { dispatcher ->
                val result = run { dispatcher.start(); kotlinx.coroutines.withTimeout(5000) { dispatcher.awaitCompletion() } }
                assertEquals(1, result.completed)
                assertEquals(0, result.failed)
                assertEquals(TaskStatus.SUCCEEDED, result.tasks.single().status)
                assertEquals(WorkerStatus.FINISHED, result.workers.single().status)
                assertEquals(1024L, result.tasks.single().progress?.logicalBytes)
                assertEquals(1.0, MonitorModel.overallProgress(result))
                val breakdown = OverallBreakdown.from(result)
                assertEquals(1024L, breakdown.skippedBytes)
                assertEquals(1.0, breakdown.skipped)
                assertEquals(0.0, breakdown.other)
                assertEquals(0L, breakdown.literalBytes)
                assertEquals(20, breakdown.segments(20).filter { it.color == MonitorColor.ANSI.BLUE }.sumOf { it.text.length })
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `partial statistics and failures are not silently classified as matched`(): Unit = kotlinx.coroutines.runBlocking {
        val task = TransferTask("file", 100)
        for (state in listOf(
            TaskState(task, TaskStatus.RUNNING, progress = TransferProgress(0, null, null, matchedBytes = 0, literalBytes = 0)),
            TaskState(task, TaskStatus.FAILED, progress = TransferProgress(0, null, null, matchedBytes = 0, literalBytes = 0)),
            TaskState(task, TaskStatus.SUCCEEDED, progress = TransferProgress(100, null, null, matchedBytes = 0)),
        )) {
            assertEquals(0.0, OverallBreakdown.from(AirliftSnapshot(listOf(state), emptyList(), false, false)).matched)
        }
    }
}
