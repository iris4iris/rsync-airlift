package iris.tools.airlift.monitor

/** Incremental ASCII terminal-control decoder. UTF-8 text is consumed as complete sequences. */
internal class AnsiInput {
    private var pending = ""
    private var pasting = false
    private var interrupted = false

    /** True once after Ctrl+C was decoded as a key. Bytes inside a bracketed paste never count. */
    fun takeInterrupt(): Boolean = interrupted.also { interrupted = false }
    fun feed(bytes: ByteArray) {
        pending += bytes.map { (it.toInt() and 255).toChar() }.joinToString("")
        if (pending.length > 65536) {
            val pasteStart = pending.indexOf("\u001b[200~")
            val pasteEnd = pending.indexOf("\u001b[201~", (pasteStart + 6).coerceAtLeast(0))
            if (pasting || pasteStart >= 0) {
                if (pasteEnd < 0) { pasting = true; pending = pending.takeLast(5) }
                else { pasting = false; pending = pending.drop(pasteEnd + 6).takeLast(4096) }
            } else pending = pending.takeLast(4096)
        }
    }
    val waitingForEscape: Boolean get() = pending.startsWith('\u001b')
    private fun take(count: Int): String = pending.take(count).also { pending = pending.drop(count) }
    fun poll(escapeExpired: Boolean = false): MonitorEvent? {
        if (pasting) {
            val end = pending.indexOf("\u001b[201~")
            if (end < 0) { pending = pending.takeLast(5); return null }
            take(end + 6); pasting = false
        }
        if (pending.isEmpty()) return null
        if (pending[0] != '\u001b') {
            val byte = pending[0].code
            val count = when { byte < 128 -> 1; byte in 0xc2..0xdf -> 2; byte in 0xe0..0xef -> 3; byte in 0xf0..0xf4 -> 4; else -> 1 }
            if (pending.length < count) return null
            val text = take(count)
            return when (byte) {
                3 -> { interrupted = true; MonitorEvent.Key(MonitorKey.EOF) }
                4 -> MonitorEvent.Key(MonitorKey.EOF)
                9 -> MonitorEvent.Key(MonitorKey.Tab)
                10, 13 -> MonitorEvent.Key(MonitorKey.Enter)
                in 32..126 -> MonitorEvent.Key(MonitorKey.Character, text[0])
                else -> MonitorEvent.Key(MonitorKey.Other)
            }
        }
        fun incomplete(): MonitorEvent? = if (escapeExpired) { take(pending.length); MonitorEvent.Key(MonitorKey.Other) } else null
        if (pending.length < 2) return incomplete()
        if (pending[1] == 'O') {
            if (pending.length < 3) return incomplete()
            val final = take(3).last()
            return MonitorEvent.Key(when (final) { 'Q' -> MonitorKey.F2; 'H' -> MonitorKey.Home; 'F' -> MonitorKey.End; else -> MonitorKey.Other })
        }
        if (pending[1] != '[') { take(2); return MonitorEvent.Key(MonitorKey.Other) }
        if (pending.startsWith("\u001b[M")) {
            if (pending.length < 6) return incomplete()
            val text = take(6)
            return mouse(text[3].code - 32, text[4].code - 32, text[5].code - 32, false)
        }
        val end = (2 until pending.length).firstOrNull { pending[it].code in 0x40..0x7e } ?: return incomplete()
        val sequence = take(end + 1)
        if (sequence == "\u001b[200~") { pasting = true; return poll() }
        val final = sequence.last()
        val parameters = sequence.substring(2, sequence.lastIndex)
        if (parameters.startsWith('<')) {
            val fields = parameters.drop(1).split(';').map { it.toIntOrNull() }
            if (fields.size != 3 || fields.any { it == null } || final !in "Mm") return MonitorEvent.Key(MonitorKey.Other)
            return mouse(fields[0]!!, fields[1]!!, fields[2]!!, final == 'm')
        }
        val key = when (final) {
            'A' -> MonitorKey.ArrowUp; 'B' -> MonitorKey.ArrowDown; 'C' -> MonitorKey.ArrowRight; 'D' -> MonitorKey.ArrowLeft
            'H' -> MonitorKey.Home; 'F' -> MonitorKey.End; 'Z' -> MonitorKey.Tab
            '~' -> when (parameters.substringBefore(';')) {
                "1", "7" -> MonitorKey.Home; "4", "8" -> MonitorKey.End; "5" -> MonitorKey.PageUp
                "6" -> MonitorKey.PageDown; "12" -> MonitorKey.F2; else -> MonitorKey.Other
            }
            else -> MonitorKey.Other
        }
        return MonitorEvent.Key(key)
    }
    private fun mouse(code: Int, column: Int, row: Int, released: Boolean): MonitorEvent {
        if (column <= 0 || row <= 0 || code < 0) return MonitorEvent.Key(MonitorKey.Other)
        val type = when {
            code and 64 != 0 -> if (code and 1 == 0) MonitorMouseAction.SCROLL_UP else MonitorMouseAction.SCROLL_DOWN
            released || code and 32 != 0 || code and 3 == 3 -> MonitorMouseAction.OTHER
            else -> MonitorMouseAction.CLICK_DOWN
        }
        return MonitorEvent.Mouse(type, if (code and 64 != 0) 4 + (code and 1) else (code and 3) + 1, column - 1, row - 1)
    }
}
