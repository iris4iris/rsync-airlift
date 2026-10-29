@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package iris.tools.airlift.monitor

import kotlinx.cinterop.*
import platform.posix.*
import iris.tools.airlift.monitor.posix.*

/** POSIX terminal lifecycle only; frame diffing and input decoding are shared.
 *
 * Output never blocks and never fails the run: bytes the terminal cannot take yet stay queued and are retried
 * on later calls, and new frames are skipped until the backlog drains. Nothing queued is dropped, so the frame
 * encoder's previous frame still matches the screen and the next diff stays valid. A hangup or terminal error
 * ends the panel through an EOF event; transfers keep running.
 */
internal class LinuxAnsiTerminal(private val device: String = "/dev/tty") : MonitorTerminal {
    private var terminal: CPointer<airlift_terminal>? = null
    private var signals: CPointer<airlift_signals>? = null
    private val frames = AnsiFrames { code -> airlift_column_width(code.toUInt()).let { if (it < 0) ansiColumnWidth(code) else it } }
    private val input = AnsiInput()
    private var lastInputAt = 0L
    private var entered = false
    private var ended = false
    private var pending = ByteArray(0)
    private var pendingOffset = 0

    /** Bytes accepted for output but not yet written to the terminal. */
    internal val backlog: Int get() = pending.size - pendingOffset

    override fun start() {
        check(terminal == null) { "Terminal already started" }
        check(getenv("TERM")?.toKString() != "dumb") { "Native TUI needs an ANSI-capable terminal" }
        terminal = airlift_terminal_open_path(device) ?: linuxError("open terminal (SSH requires ssh -t)")
        signals = airlift_install_signals() ?: linuxError("install terminal signal handlers")
        entered = true
        ended = false
        enqueue("\u001b[?1049h\u001b[?25l\u001b[?7l\u001b[?1000h\u001b[?1006h\u001b[?2004h")
        flush()
    }
    override fun size(): MonitorSize = memScoped {
        val fd = requireNotNull(terminal).pointed.fd
        val columns = alloc<IntVar>(); val rows = alloc<IntVar>()
        if (airlift_terminal_size(fd, columns.ptr, rows.ptr) != 0) return@memScoped MonitorSize(80, 24)
        MonitorSize(columns.value.coerceIn(1, 1000), rows.value.coerceIn(1, 500))
    }
    override fun present(frame: MonitorFrame) {
        if (ended || !flush()) return
        enqueue(frames.encode(frame))
        flush()
    }
    override fun pollEvent(): MonitorEvent? {
        if (airlift_stopping() != 0 || ended) return MonitorEvent.Key(MonitorKey.EOF)
        flush()
        val fd = requireNotNull(terminal).pointed.fd
        val bytes = ByteArray(4096)
        var total = 0
        while (total < 65536) {
            val count = bytes.usePinned { read(fd, it.addressOf(0), bytes.size.toULong()) }
            if (count < 0) {
                val error = errno
                if (error == EINTR) continue
                if (error == EAGAIN) break
                ended = true // EIO after a hangup, or another terminal failure
                return MonitorEvent.Key(MonitorKey.EOF)
            }
            if (count == 0L) { ended = true; return MonitorEvent.Key(MonitorKey.EOF) }
            input.feed(bytes.copyOf(count.toInt()))
            lastInputAt = monitorMonotonicNanos()
            total += count.toInt()
        }
        val event = input.poll(input.waitingForEscape && monitorMonotonicNanos() - lastInputAt > 100_000_000L)
        // Ctrl+C typed outside a bracketed paste stops the dispatcher; pasted bytes are never commands.
        if (input.takeInterrupt()) airlift_request_stop()
        return event
    }
    private fun enqueue(text: String) {
        if (text.isEmpty()) return
        val bytes = text.encodeToByteArray()
        pending = if (backlog > 0) pending.copyOfRange(pendingOffset, pending.size) + bytes else bytes
        pendingOffset = 0
    }
    /** Writes queued output without blocking; true when nothing remains queued. */
    private fun flush(): Boolean {
        val fd = terminal?.pointed?.fd ?: return true
        while (pendingOffset < pending.size) {
            val count = pending.usePinned { write(fd, it.addressOf(pendingOffset), (pending.size - pendingOffset).toULong()) }
            if (count > 0) { pendingOffset += count.toInt(); continue }
            if (count == 0L) return false
            val error = errno
            if (error == EINTR) continue
            if (error == EAGAIN) return false
            ended = true // the panel ends at the next pollEvent; queued bytes can no longer be delivered
            break
        }
        pending = ByteArray(0); pendingOffset = 0
        return true
    }
    /** Gives a slow terminal a bounded chance to take the backlog and the restore sequence. */
    private fun drain(timeoutMillis: Long) {
        val fd = terminal?.pointed?.fd ?: return
        val deadline = monitorMonotonicNanos() + timeoutMillis * 1_000_000
        while (!flush() && !ended) {
            val remaining = (deadline - monitorMonotonicNanos()) / 1_000_000
            if (remaining <= 0) return
            airlift_wait_fd(fd, POLLOUT, remaining.coerceAtMost(100).toInt())
        }
    }
    override fun close() {
        val current = terminal ?: return
        try {
            if (entered && !ended) {
                enqueue("\u001b[0m\u001b[?2004l\u001b[?1006l\u001b[?1000l\u001b[?7h\u001b[?25h\u001b[?1049l")
                drain(1000)
            }
        } finally {
            pending = ByteArray(0); pendingOffset = 0
            airlift_terminal_close(current)
            terminal = null
            signals?.let { airlift_restore_signals(it) }; signals = null
            entered = false
        }
    }
}
