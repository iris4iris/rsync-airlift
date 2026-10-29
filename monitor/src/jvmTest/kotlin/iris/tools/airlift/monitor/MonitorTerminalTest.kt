package iris.tools.airlift.monitor

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.input.MouseAction
import com.googlecode.lanterna.input.MouseActionType
import com.googlecode.lanterna.TerminalPosition
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal
import iris.tools.airlift.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class MonitorTerminalTest {
    private class Source : MonitorSource {
        var value = AirliftSnapshot(emptyList(), emptyList(), false, false, 4)
        override suspend fun snapshot() = value
        override suspend fun pause() { value = value.copy(paused = true) }
        override suspend fun resume() { value = value.copy(paused = false) }
        override suspend fun setWorkerCount(count: Int) { value = value.copy(targetWorkers = count) }
    }

    @Test fun `monitor runs through portable terminal and preserves input and detach semantics`(): Unit = runBlocking {
        val source = Source()
        val frames = mutableListOf<MonitorFrame>()
        // null separates input bursts; every burst is drained in one tick and rendered once.
        val events = ArrayDeque(listOf(
            MonitorEvent.Key(MonitorKey.Character, 'p'),
            MonitorEvent.Key(MonitorKey.Character, '+'),
            null,
            MonitorEvent.Mouse(MonitorMouseAction.CLICK_DOWN, 1, 14, 7),
            null,
            MonitorEvent.Key(MonitorKey.Character, 'q'),
        ))
        var started = false
        var closed = false
        val terminal = object : MonitorTerminal {
            override fun start() { started = true }
            override fun size() = if (frames.size < 2) MonitorSize(80, 24) else MonitorSize(160, 32)
            override fun present(frame: MonitorFrame) { frames += frame }
            override fun pollEvent(): MonitorEvent? = events.removeFirstOrNull()
            override fun close() { closed = true }
        }
        AirliftMonitor(source).run(terminal)
        assertTrue(started && closed)
        assertTrue(source.value.paused)
        assertEquals(5, source.value.targetWorkers)
        assertFalse(source.value.cancelled)
        assertEquals(MonitorSize(160, 32), frames.last().size)
        assertTrue(frames.last().runs.any { it.text.endsWith("| QUEUE") })
    }

    @Test fun `terminal is restored if startup fails`(): Unit = runBlocking {
        var closed = false
        val terminal = object : MonitorTerminal {
            override fun start(): Unit = error("startup failure")
            override fun size(): MonitorSize = error("not started")
            override fun present(frame: MonitorFrame): Unit = error("not started")
            override fun pollEvent(): MonitorEvent? = null
            override fun close() { closed = true }
        }
        assertFailsWith<IllegalStateException> { AirliftMonitor(Source()).run(terminal) }
        assertTrue(closed)
    }

    @Test fun `adapter maps keyboard and mouse to portable events`() {
        assertEquals(MonitorEvent.Key(MonitorKey.ArrowDown), KeyStroke(KeyType.ArrowDown).toMonitorEvent())
        assertEquals(MonitorEvent.Key(MonitorKey.Character, '+'), KeyStroke('+', false, false).toMonitorEvent())
        assertEquals(MonitorEvent.Mouse(MonitorMouseAction.SCROLL_DOWN, 0, 93, 12),
            MouseAction(MouseActionType.SCROLL_DOWN, 0, TerminalPosition(93, 12)).toMonitorEvent())
    }

    @Test fun `column clipping is single pass and never splits surrogate pairs`() {
        assertEquals("a界" to 3, clipColumns("a界b", 3))
        assertEquals("a" to 1, clipColumns("a界b", 2))
        val (emoji, used) = clipColumns("😀😀😀", 3)
        assertFalse(emoji.last().isHighSurrogate())
        assertTrue(used <= 3)
        // A multi-megabyte line used to be trimmed one character at a time with a full re-measure each step.
        val long = "x".repeat(2_000_000)
        val started = System.nanoTime()
        assertEquals(200, clipColumns(long, 200).second)
        assertTrue(System.nanoTime() - started < 1_000_000_000)
        assertSame(long, clipColumns(long, 3_000_000).first)
    }

    @Test fun `frame adapter clips wide text sanitizes controls and resets stale backgrounds`() {
        TerminalScreen(DefaultVirtualTerminal(TerminalSize(8, 3))).use { screen ->
            screen.startScreen()
            val frame = MonitorFrame(MonitorSize(8, 3))
            frame.foregroundColor = MonitorColor.ANSI.CYAN
            frame.backgroundColor = MonitorColor.RGB(135, 88, 44)
            frame.putString(0, 0, "界界界", 4, pad = true)
            frame.putString(0, 1, "a\u001bb\nc", 8, pad = true)
            drawFrame(screen, frame)
            assertEquals("界", screen.getBackCharacter(0, 0).characterString)
            assertEquals("界", screen.getBackCharacter(2, 0).characterString)
            assertEquals(TextColor.ANSI.BLACK, screen.getBackCharacter(4, 0).backgroundColor)
            assertEquals(TextColor.RGB(135, 88, 44), screen.getBackCharacter(3, 0).backgroundColor)
            assertEquals("a b c   ", (0..7).joinToString("") { screen.getBackCharacter(it, 1).characterString })
            drawFrame(screen, MonitorFrame(MonitorSize(8, 3)))
            assertEquals(TextColor.ANSI.BLACK, screen.getBackCharacter(0, 0).backgroundColor)
            assertEquals(" ", screen.getBackCharacter(0, 0).characterString)
        }
    }
}
