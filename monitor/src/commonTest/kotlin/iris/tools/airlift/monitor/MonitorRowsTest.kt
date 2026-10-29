package iris.tools.airlift.monitor

import iris.tools.airlift.*
import kotlin.test.*

class MonitorRowsTest {
    private val source = object : MonitorSource {
        override suspend fun snapshot() = AirliftSnapshot(emptyList(), emptyList(), false, false)
        override suspend fun pause() {}
        override suspend fun resume() {}
        override suspend fun setWorkerCount(count: Int) {}
    }
    private fun queued(name: String, status: TaskStatus = TaskStatus.QUEUED) = TaskState(TransferTask(name, 10), status)
    private fun MonitorFrame.text() = runs.joinToString("\n") { it.text }
    private fun MonitorFrame.selectedText() = runs.first { it.background == MonitorColor.ANSI.BLUE }.text

    @Test fun `selection follows its row when the list shifts and falls back to the old position`() {
        val renderer = MonitorRenderer(source)
        val model = MonitorModel().apply { view = MonitorView.QUEUE }
        val all = listOf("a", "b", "c", "d").map { queued(it) }
        fun snapshot(tasks: List<TaskState>) = AirliftSnapshot(tasks, emptyList(), false, false)
        renderer.render(MonitorSize(80, 24), snapshot(all), model)
        model.selected = 2
        assertTrue(renderer.render(MonitorSize(80, 24), snapshot(all), model).selectedText().endsWith(" c"))
        // "a" finished and left the queue: the highlight stays on "c", now at index 1.
        val shifted = snapshot(all.drop(1) + queued("a", TaskStatus.SUCCEEDED))
        assertTrue(renderer.render(MonitorSize(80, 24), shifted, model).selectedText().endsWith(" c"))
        assertEquals(1, model.selected)
        // "c" finished too: keep the position rather than jumping to the top.
        val gone = snapshot(listOf(all[1], all[3], queued("c", TaskStatus.SUCCEEDED)))
        assertTrue(renderer.render(MonitorSize(80, 24), gone, model).selectedText().endsWith(" d"))
        // Explicit navigation still works by position.
        model.selected = 0
        assertTrue(renderer.render(MonitorSize(80, 24), gone, model).selectedText().endsWith(" b"))
    }

    @Test fun `only the visible page of a long queue is materialized`() {
        val tasks = List(200_000) { queued("dir/file-$it") }
        val snapshot = AirliftSnapshot(tasks, emptyList(), false, false)
        val model = MonitorModel().apply { view = MonitorView.QUEUE; details = false }
        val list = model.rowList(snapshot)
        assertEquals(200_000, list.size)
        assertEquals(150_000, list.indexOf("dir/file-150000"))
        val frame = MonitorRenderer(source).render(MonitorSize(100, 30), snapshot, model)
        assertEquals(19, frame.runs.count { "dir/file-" in it.text })
        assertSame(list, model.rowList(snapshot), "rows are cached per snapshot")
    }

    @Test fun `aggregates match the core snapshot counters`() {
        val tasks = listOf(
            TaskState(TransferTask("a", 100, fileTotal = 3), TaskStatus.SUCCEEDED),
            TaskState(TransferTask("b", 50), TaskStatus.FAILED, error = "denied"),
            TaskState(TransferTask("c", 200), TaskStatus.RUNNING, progress = TransferProgress(300, null, null)),
            TaskState(TransferTask("d"), TaskStatus.QUEUED),
            TaskState(TransferTask("e", 10), TaskStatus.SUCCEEDED, warning = "vanished (code 24)."),
        )
        val snapshot = AirliftSnapshot(tasks, emptyList(), false, false)
        val stats = MonitorStats.of(snapshot)
        assertEquals(snapshot.completed, stats.completedFiles)
        assertEquals(snapshot.failed, stats.failedFiles)
        assertEquals(tasks.sumOf { it.task.fileCount }, stats.totalFiles)
        assertEquals(100L + 200 + 10, stats.processedBytes) // Running progress is capped at its size.
        assertFalse(stats.allSizesKnown)
        assertEquals(1, stats.warnings)
        assertEquals(listOf(tasks[2]), stats.running)
    }

    @Test fun `warnings are listed separately and never counted as failures`() {
        val warning = "Some source files vanished before they were copied (code 24)."
        val tasks = listOf(
            TaskState(TransferTask("ok", 10), TaskStatus.SUCCEEDED, 1, warning = warning),
            TaskState(TransferTask("bad", 10), TaskStatus.FAILED, 1, error = "permission denied"),
        )
        val workers = listOf(WorkerState(1, WorkerStatus.FINISHED, "ok"),
            WorkerState(2, WorkerStatus.CONNECTING, null, "SSH connection lost; reconnecting (2/3)"))
        val snapshot = AirliftSnapshot(tasks, workers, false, false, 2, startedAtMillis = 1, finishedAtMillis = 2)
        val model = MonitorModel().apply { view = MonitorView.ERRORS }
        val rows = model.rows(snapshot)
        assertEquals(listOf("bad", "worker-2", "ok"), rows.map { it.key })
        assertEquals("WARNING attempt 1: ok | $warning", rows[2].text)
        assertNull(rows[2].error)
        assertEquals("SSH connection lost; reconnecting (2/3)", rows[1].warning)
        assertNull(rows[1].error, "a reconnecting worker is not a failure")
        val renderer = MonitorRenderer(source)
        val frame = renderer.render(MonitorSize(100, 28), snapshot, model)
        assertTrue(frame.runs.any { it.text.startsWith("WARNING attempt 1: ok") && it.foreground == MonitorColor.ANSI.YELLOW })
        assertTrue(frame.runs.any { it.text.startsWith("FAILED attempt 1: bad") && it.foreground == MonitorColor.ANSI.RED })
        assertContains(frame.text(), "Files 1/2 | Failed 1 | Warnings 1 |")
        assertContains(frame.text(), "FINISHED WITH ERRORS")
        model.selected = 2
        assertTrue(renderer.render(MonitorSize(100, 28), snapshot, model).runs.any {
            it.text == "Warning: $warning" && it.foreground == MonitorColor.ANSI.YELLOW })
        val onlyWarnings = snapshot.copy(tasks = tasks.take(1), workers = workers.take(1))
        val clean = renderer.render(MonitorSize(100, 28), onlyWarnings, MonitorModel())
        assertContains(clean.text(), "FINISHED WITH WARNINGS")
        assertFalse("Failed" in clean.runs.single { it.text.startsWith("Workers ") }.text)
    }

    @Test fun `reconnecting worker row is not shown as failed`() {
        val task = TaskState(TransferTask("job", 100), TaskStatus.QUEUED)
        val worker = WorkerState(3, WorkerStatus.CONNECTING, task.task.id, "SSH connection lost; reconnecting (2/3)")
        val snapshot = AirliftSnapshot(listOf(task), listOf(worker), false, false, 1)
        val row = MonitorModel().rows(snapshot).single()
        assertContains(row.text, "CONNECTING")
        assertFalse("FAILED" in row.text)
        assertNull(row.error)
        val frame = MonitorRenderer(source).render(MonitorSize(100, 28), snapshot, MonitorModel())
        assertTrue(frame.runs.any { it.text == "Warning: SSH connection lost; reconnecting (2/3)" })
        assertFalse(frame.runs.any { it.text.startsWith("Error:") })
    }

    @Test fun `plus stops at the worker limit with a clear message`() = kotlinx.coroutines.test.runTest {
        val requested = mutableListOf<Int>()
        val full = object : MonitorSource {
            override suspend fun snapshot() = AirliftSnapshot(emptyList(), emptyList(), false, false, AirliftConfig.MAX_WORKERS)
            override suspend fun pause() {}
            override suspend fun resume() {}
            override suspend fun setWorkerCount(count: Int) { requested += count }
        }
        val error = assertFailsWith<IllegalStateException> { MonitorModel().handle('+', full) }
        assertContains(error.message!!, "${AirliftConfig.MAX_WORKERS}")
        assertTrue(requested.isEmpty())
    }
}
