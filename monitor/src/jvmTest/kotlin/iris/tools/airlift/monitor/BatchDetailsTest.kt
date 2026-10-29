package iris.tools.airlift.monitor

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal
import iris.tools.airlift.*
import kotlin.test.*

class BatchDetailsTest {
    @Test fun `successful batch shows all processed rather than stale last announced position`() {
        // A monitor copy carries only the file total; the dispatcher reports the 1-based index.
        val task = TransferTask("f0", 1000, fileTotal = 222)
        val state = TaskState(task, TaskStatus.RUNNING, progress = TransferProgress(1000, 100.0, null,
            currentFile = "f52", transferredFiles = 6, currentFileIndex = 53))
        val snapshot = AirliftSnapshot(listOf(state), listOf(WorkerState(4, WorkerStatus.TRANSFERRING, task.id)), false, false)
        val source = object : MonitorSource {
            override suspend fun snapshot() = snapshot
            override suspend fun pause() {}
            override suspend fun resume() {}
            override suspend fun setWorkerCount(count: Int) {}
        }
        TerminalScreen(DefaultVirtualTerminal(TerminalSize(140, 24))).use { screen ->
            screen.startScreen()
            val monitor = AirliftMonitor(source)
            val model = MonitorModel()
            fun details() = (0 until 140).joinToString("") { screen.getBackCharacter(it, 20).characterString }
            monitor.render(screen, snapshot, model)
            assertContains(details(), "file 53/222")
            monitor.render(screen, snapshot.copy(tasks = listOf(state.copy(status = TaskStatus.SUCCEEDED))), model)
            assertContains(details(), "Processed 222/222")
            assertContains(details(), "sent 6")
            assertFalse("file 53/222" in details())
        }
    }
}
