@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package iris.tools.airlift.monitor

import kotlinx.cinterop.*
import platform.posix.*
import kotlinx.coroutines.*
import iris.tools.airlift.monitor.posix.*
import kotlin.test.*

/** These two lifecycle checks require a PTY; headless runs still execute shared ANSI tests. */
class LinuxAnsiTerminalTest {
    @Test fun terminalRestoresRawModeAndAlternateScreen() {
        if (!hasInteractiveConsole()) return
        memScoped {
            val before = alloc<termios>()
            val during = alloc<termios>()
            val after = alloc<termios>()
            assertEquals(0, tcgetattr(STDIN_FILENO, before.ptr))
            val terminal = LinuxAnsiTerminal()
            try {
                terminal.start()
                assertEquals(0, tcgetattr(STDIN_FILENO, during.ptr))
                assertEquals(0u, during.c_lflag and (ECHO or ICANON).toUInt())
                val size = terminal.size()
                assertTrue(size.columns > 0 && size.rows > 0)
                val frame = MonitorFrame(size).apply { putString(0, 0, "Native terminal lifecycle test") }
                terminal.present(frame)
                terminal.present(frame)
            } finally { terminal.close(); terminal.close() }
            assertEquals(0, tcgetattr(STDIN_FILENO, after.ptr))
            assertEquals(before.c_iflag, after.c_iflag)
            assertEquals(before.c_oflag, after.c_oflag)
            assertEquals(before.c_cflag, after.c_cflag)
            assertEquals(before.c_lflag, after.c_lflag)
            repeat(NCCS) { assertEquals(before.c_cc[it], after.c_cc[it]) }
        }
    }

    @Test fun termSignalClosesTerminalAndRestoresMode() {
        if (!hasInteractiveConsole()) return
        memScoped {
            val before = alloc<termios>()
            val after = alloc<termios>()
            assertEquals(0, tcgetattr(STDIN_FILENO, before.ptr))
            val terminal = LinuxAnsiTerminal()
            try {
                terminal.start()
                assertEquals(0, kill(getpid(), SIGTERM))
                assertEquals(MonitorEvent.Key(MonitorKey.EOF), terminal.pollEvent())
            } finally { terminal.close() }
            assertEquals(0, tcgetattr(STDIN_FILENO, after.ptr))
            assertEquals(before.c_lflag, after.c_lflag)
        }
    }

    /** A pseudo-terminal nobody reads stands in for a stalled SSH session; runs without a controlling terminal. */
    private fun withPseudoTerminal(block: (master: Int, terminal: LinuxAnsiTerminal) -> Unit) {
        val master = posix_openpt(O_RDWR or O_NOCTTY)
        if (master < 0) return
        try {
            assertEquals(0, grantpt(master)); assertEquals(0, unlockpt(master))
            assertEquals(0, fcntl(master, F_SETFL, fcntl(master, F_GETFL) or O_NONBLOCK))
            if (getenv("TERM")?.toKString() == "dumb") setenv("TERM", "xterm", 1)
            val terminal = LinuxAnsiTerminal(requireNotNull(ptsname(master)).toKString())
            terminal.start()
            try { block(master, terminal) } finally { terminal.close() }
        } finally { close(master) }
    }
    private fun drainMaster(master: Int): Int {
        val buffer = ByteArray(65536)
        var total = 0
        while (true) {
            val count = buffer.usePinned { read(master, it.addressOf(0), buffer.size.convert()) }
            if (count <= 0) return total
            total += count.toInt()
        }
    }
    private fun busyFrame(index: Int) = MonitorFrame(MonitorSize(200, 60)).apply {
        repeat(60) { row -> putString(0, row, "frame $index row $row ".repeat(10)) }
    }

    @Test fun stalledTerminalQueuesOutputWithoutFailingAndCatchesUp() = withPseudoTerminal { master, terminal ->
        repeat(40) { terminal.present(busyFrame(it)) }
        assertTrue(terminal.backlog > 0, "the pseudo-terminal buffer should be full")
        val deadline = monitorMonotonicNanos() + 5_000_000_000L
        var received = 0
        while (terminal.backlog > 0 && monitorMonotonicNanos() < deadline) {
            received += drainMaster(master)
            terminal.present(busyFrame(1000))
        }
        assertEquals(0, terminal.backlog)
        received += drainMaster(master)
        assertTrue(received > 10_000)
        assertNull(terminal.pollEvent())
    }

    @Test fun hangupEndsPanelInsteadOfThrowing() {
        val master = posix_openpt(O_RDWR or O_NOCTTY)
        if (master < 0) return
        if (getenv("TERM")?.toKString() == "dumb") setenv("TERM", "xterm", 1)
        assertEquals(0, grantpt(master)); assertEquals(0, unlockpt(master))
        val terminal = LinuxAnsiTerminal(requireNotNull(ptsname(master)).toKString())
        try {
            terminal.start()
            close(master)
            terminal.present(busyFrame(1))
            assertEquals(MonitorEvent.Key(MonitorKey.EOF), terminal.pollEvent())
            assertEquals(MonitorEvent.Key(MonitorKey.EOF), terminal.pollEvent())
        } finally { terminal.close() }
    }

    @Test fun pastedControlCDoesNotStopButTypedControlCDoes() = withPseudoTerminal { master, terminal ->
        fun type(text: String) { val bytes = text.encodeToByteArray(); bytes.usePinned { write(master, it.addressOf(0), bytes.size.convert()) } }
        fun nextEvent(): MonitorEvent? {
            val deadline = monitorMonotonicNanos() + 2_000_000_000L
            while (monitorMonotonicNanos() < deadline) { terminal.pollEvent()?.let { return it }; usleep(10_000u) }
            return null
        }
        type("\u001b[200~abc\u0003def\u001b[201~x")
        assertEquals(MonitorEvent.Key(MonitorKey.Character, 'x'), nextEvent())
        assertEquals(0, airlift_stopping())
        type("\u0003")
        assertEquals(MonitorEvent.Key(MonitorKey.EOF), nextEvent())
        assertNotEquals(0, airlift_stopping())
    }

    @Test fun openingTerminalPreservesPendingServiceShutdown(): Unit = runBlocking {
        if (!hasInteractiveConsole()) return@runBlocking
        val shutdown = CompletableDeferred<Unit>()
        val registration = monitorApplicationPlatform().onShutdown { shutdown.complete(Unit) }
        val terminal = LinuxAnsiTerminal()
        try {
            airlift_request_stop()
            terminal.start()
            assertEquals(MonitorEvent.Key(MonitorKey.EOF), terminal.pollEvent())
            terminal.close()
            withTimeout(2000) { shutdown.await() }
        } finally { terminal.close(); registration.remove() }
    }
}
