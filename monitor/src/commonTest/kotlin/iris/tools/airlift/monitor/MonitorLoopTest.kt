package iris.tools.airlift.monitor

import iris.tools.airlift.*
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MonitorLoopTest {
    private val tasks = (1..40).map { TaskState(TransferTask("file-$it", 100), TaskStatus.RUNNING) }
    private val snapshot = AirliftSnapshot(tasks, tasks.mapIndexed { i, task -> WorkerState(i + 1, WorkerStatus.TRANSFERRING, task.task.id) }, false, false, 40)

    private open inner class CountingSource : MonitorSource {
        var calls = 0
        override suspend fun snapshot(): AirliftSnapshot { calls++; return snapshot }
        override suspend fun pause() {}
        override suspend fun resume() {}
        override suspend fun setWorkerCount(count: Int) {}
    }

    private class ScriptedTerminal(events: List<MonitorEvent?>, private val size: MonitorSize = MonitorSize(80, 24)) : MonitorTerminal {
        val queue = ArrayDeque(events)
        val frames = mutableListOf<MonitorFrame>()
        var closed = false
        var quitWhen: () -> Boolean = { false }
        override fun start() {}
        override fun size() = size
        override fun present(frame: MonitorFrame) { frames += frame }
        override fun pollEvent(): MonitorEvent? =
            if (quitWhen()) MonitorEvent.Key(MonitorKey.Character, 'q') else queue.removeFirstOrNull()
        override fun close() { closed = true }
    }

    private fun TestScope.monitor(source: MonitorSource) = AirliftMonitor(source).also { monitor ->
        monitor.clock = { testScheduler.currentTime * 1_000_000 }
    }

    private fun MonitorFrame.selectedText() = runs.first { it.background == MonitorColor.ANSI.BLUE }.text

    @Test fun `a burst of keys is drained in one tick without fetching snapshots per event`() = runTest {
        val source = CountingSource()
        val terminal = ScriptedTerminal(List(30) { MonitorEvent.Key(MonitorKey.ArrowDown) } +
            List(5) { MonitorEvent.Mouse(MonitorMouseAction.SCROLL_DOWN, 5, 3, 10) } + null +
            MonitorEvent.Key(MonitorKey.Character, 'q'))
        monitor(source).run(terminal)
        assertEquals(1, source.calls, "navigation and mouse input reuse the tick's snapshot")
        assertEquals(2, terminal.frames.size, "one render before and one after the burst")
        assertTrue(terminal.frames.last().selectedText().startsWith("036 ["))
        assertTrue(terminal.closed)
    }

    @Test fun `snapshots refresh on the interval while idle`() = runTest {
        val source = CountingSource()
        val terminal = ScriptedTerminal(emptyList())
        terminal.quitWhen = { testScheduler.currentTime >= 1_000 }
        monitor(source).run(terminal)
        assertTrue(source.calls in 5..7, "about one snapshot per 200 ms, got ${source.calls}")
    }

    @Test fun `commands refresh immediately and End then Down cannot overflow`() = runTest {
        val source = object : CountingSource() {
            var paused = false
            override suspend fun snapshot(): AirliftSnapshot { calls++; return snapshot.copy(paused = paused) }
            override suspend fun pause() { paused = true }
        }
        val terminal = ScriptedTerminal(listOf(MonitorEvent.Key(MonitorKey.End), MonitorEvent.Key(MonitorKey.ArrowDown),
            MonitorEvent.Key(MonitorKey.Character, 'p'), null, MonitorEvent.Key(MonitorKey.Character, 'q')))
        monitor(source).run(terminal)
        assertTrue(terminal.frames.last().runs.any { "PAUSED" in it.text })
        assertTrue(terminal.frames.last().selectedText().startsWith("040 ["))
    }

    @Test fun `lost connection keeps the last frame retries and gives up after ten seconds`() = runTest {
        var failing = false
        val source = object : CountingSource() {
            override suspend fun snapshot(): AirliftSnapshot {
                calls++
                if (failing) error("Unexpected end of monitor connection")
                return snapshot
            }
        }
        val terminal = ScriptedTerminal(emptyList())
        terminal.quitWhen = { if (testScheduler.currentTime >= 500) failing = true; false }
        val error = assertFailsWith<IllegalStateException> { monitor(source).run(terminal) }
        assertContains(error.message!!, "Unexpected end")
        assertTrue(testScheduler.currentTime in 10_000..12_500, "gave up after ${testScheduler.currentTime} ms")
        assertTrue(terminal.frames.last().runs.any { it.text.startsWith(CONNECTION_LOST) })
        assertTrue(terminal.frames.last().runs.any { "DETAILS WORKER 001" in it.text }, "last data stays visible")
        assertTrue(source.calls in 10..16, "retries about once per second, got ${source.calls}")
        assertTrue(terminal.closed)
    }

    @Test fun `connection recovers and clears the notice`() = runTest {
        val source = object : CountingSource() {
            override suspend fun snapshot(): AirliftSnapshot {
                calls++
                if (testScheduler.currentTime in 300..3_000) error("Monitor read timed out")
                return snapshot
            }
        }
        val terminal = ScriptedTerminal(emptyList())
        terminal.quitWhen = { testScheduler.currentTime >= 5_000 }
        monitor(source).run(terminal)
        assertTrue(terminal.frames.any { frame -> frame.runs.any { it.text.startsWith(CONNECTION_LOST) } })
        assertFalse(terminal.frames.last().runs.any { it.text.startsWith(CONNECTION_LOST) })
    }

    @Test fun `first snapshot failure still exits and restores the terminal`() = runTest {
        val terminal = ScriptedTerminal(emptyList())
        val source = object : CountingSource() {
            override suspend fun snapshot(): AirliftSnapshot = error("Connection refused")
        }
        assertFailsWith<IllegalStateException> { monitor(source).run(terminal) }
        assertTrue(terminal.closed)
        assertTrue(terminal.frames.isEmpty())
    }

    @Test fun `failed command message is cleared by the next successful command or after a few seconds`() = runTest {
        val source = object : CountingSource() {
            override suspend fun pause() = error("dispatcher busy")
        }
        val terminal = ScriptedTerminal(listOf(MonitorEvent.Key(MonitorKey.Character, 'p'), null))
        terminal.quitWhen = { testScheduler.currentTime >= 6_000 }
        monitor(source).run(terminal)
        assertTrue(terminal.frames.any { frame -> frame.runs.any { it.text == "dispatcher busy" } })
        assertFalse(terminal.frames.last().runs.any { it.text == "dispatcher busy" })

        val model = MonitorModel()
        model.message = "dispatcher busy"
        model.expireMessage(0)
        model.expireMessage(4_999_999_999)
        assertEquals("dispatcher busy", model.message)
        model.expireMessage(5_000_000_000)
        assertEquals("", model.message)
    }
}
