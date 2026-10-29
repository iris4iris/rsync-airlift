package iris.tools.airlift.monitor

import iris.tools.airlift.*
import iris.tools.airlift.process.*
import kotlinx.coroutines.*
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

internal suspend fun readManifestText(path: Path): String = withContext(preparationDispatcher()) {
    SystemFileSystem.source(path).buffered().use { it.readByteArray().decodeToString(throwOnInvalidSequence = true) }
}

/** Exit status of the discovery script when neither the literal path nor its alternative exists. */
private const val MISSING_SOURCE = 3

internal suspend fun readInventory(config: AirliftConfig, query: SourceQuery,
    launcher: ProcessLauncher = preparationProcesses()): SourceListing {
    val command = buildList {
        add(config.sshExecutable)
        addAll(listOf("-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
            "-o", "ConnectTimeout=${config.connectTimeoutSeconds}", "-o", "ServerAliveInterval=30",
            "-o", "ServerAliveCountMax=3", "-p", config.port.toString()))
        config.identityFile?.let { addAll(listOf("-i", it.toString())) }
        // OpenSSH keeps the first value of an option, so Airlift's own options above take precedence.
        addAll(config.sshArguments)
        addAll(listOf(config.host, inventoryCommand(query)))
    }
    // Register the resource before cancellation can interrupt the startup handover.
    var process: ChildProcess? = null
    try {
        withContext(NonCancellable) { process = launcher.start(SshAuthentication.configure(ProcessSpec(command))) }
        currentCoroutineContext().ensureActive()
        val child = requireNotNull(process)
        child.stdin?.close()
        return coroutineScope {
            // Drain stderr concurrently so a verbose SSH failure cannot block stdout.
            val errors = async { child.stderr?.readTail(4096).orEmpty() }
            val decoder = InventoryDecoder()
            val output = requireNotNull(child.stdout)
            val chunk = ByteArray(65536)
            while (true) {
                val count = output.read(chunk)
                if (count < 0) break
                decoder.accept(chunk, count)
            }
            val exit = child.awaitExit()
            val error = errors.await().trim()
            when {
                exit == MISSING_SOURCE && !decoder.started -> error("Source does not exist on ${config.host}: ${query.path}")
                exit != 0 -> error("Cannot collect source files over SSH (exit $exit): " + error.ifEmpty { "no diagnostic output" } +
                    if (!decoder.started) ". Remote discovery needs a POSIX sh and GNU find on ${config.host}." else "")
            }
            decoder.finish()
        }
    } finally {
        withContext(NonCancellable) { process?.close() }
    }
}

/**
 * Remote discovery script, run as `sh -c '<script>'` by any login shell (sh, bash, zsh, fish, tcsh).
 * The script contains no single quote, '!', newline or double backslash; paths are passed as printf octal escapes.
 * Output: a header record (F/D literal, M mask base, UF/UD unescaped literal), then NUL records from GNU find.
 */
internal fun inventoryCommand(query: SourceQuery): String {
    val listing = "-ignore_readdir_race -printf \"%y\\t%s\\t%P\\0\""
    val single = "-maxdepth 0 -printf \"%s\\t%f\\0\""
    fun assign(variable: String, value: String): String {
        require(value.startsWith('/')) { "Remote paths must be absolute: $value" }
        return "$variable=\$(printf \"${printfOctal(value)}\"); "
    }
    fun literal(variable: String, header: String) =
        "if [ -L \"\$$variable\" ] || [ -f \"\$$variable\" ]; then printf \"${header}F\\0\"; exec find \"\$$variable\" $single; fi; " +
            "if [ -d \"\$$variable\" ]; then printf \"${header}D\\0\"; exec find \"\$$variable\" $listing; fi; "
    val script = buildString {
        append("LC_ALL=C; export LC_ALL; ")
        append(assign("p", query.path)).append(literal("p", ""))
        when {
            query.base != null -> {
                append(assign("b", query.base))
                val depth = query.maxDepth?.let { "-maxdepth $it " }.orEmpty()
                append("if [ -d \"\$b\" ]; then printf \"M\\0\"; exec find \"\$b\" $depth$listing; fi; ")
            }
            query.unescaped != null -> append(assign("u", query.unescaped)).append(literal("u", "U"))
        }
        append("echo \"Source does not exist\" >&2; exit $MISSING_SOURCE")
    }
    check(script.none { it == '\'' || it == '!' || it == '\n' || it == '\r' } && "\\\\" !in script) { "Unsafe remote discovery script" }
    return "sh -c '$script'"
}

/** Printf format producing exactly [value]'s UTF-8 bytes; only shell-neutral characters stay unescaped. */
private fun printfOctal(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val code = byte.toInt() and 0xff
        val char = code.toChar()
        if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "/._-+,=:@") append(char)
        else append('\\').append(code.toString(8).padStart(3, '0'))
    }
}

/** Decodes NUL records as they arrive. Paths that are not valid UTF-8 are skipped, reported and block collapse. */
internal class InventoryDecoder {
    private var header: String? = null
    private val sizes = linkedMapOf<String, Long>()
    private val directories = linkedSetOf<String>()
    private val others = linkedSetOf<String>()
    private val incomplete = linkedSetOf<String>()
    private val skippedKeys = hashSetOf<String>()
    private val skipped = mutableListOf<String>()
    private var pending = ByteArray(1024)
    private var length = 0
    val started: Boolean get() = header != null

    fun accept(bytes: ByteArray, count: Int = bytes.size) {
        var start = 0
        for (index in 0 until count) {
            if (bytes[index] != 0.toByte()) continue
            if (length == 0) record(bytes, start, index)
            else { append(bytes, start, index); record(pending, 0, length); length = 0 }
            start = index + 1
        }
        if (start < count) append(bytes, start, count)
    }

    fun finish(): SourceListing {
        check(length == 0) { "Incomplete SSH file inventory" }
        val head = header ?: throw IllegalStateException("Invalid SSH file inventory")
        val match = when (head) { "M" -> SourceMatch.MASK; "UF", "UD" -> SourceMatch.UNESCAPED; else -> SourceMatch.LITERAL }
        return SourceListing(match, FileInventory(head.endsWith('F'), sizes, directories, others, incomplete,
            skipped.take(1000), skipped.size))
    }

    private fun append(bytes: ByteArray, from: Int, to: Int) {
        val required = length + (to - from)
        require(required <= 4 * 1024 * 1024) { "Invalid SSH inventory record" }
        if (required > pending.size) pending = pending.copyOf(maxOf(required, pending.size * 2))
        bytes.copyInto(pending, length, from, to)
        length = required
    }

    private fun record(bytes: ByteArray, from: Int, to: Int) {
        val head = header
        if (head == null) {
            val text = bytes.decodeToString(from, to)
            if (text !in setOf("F", "D", "M", "UF", "UD")) throw IllegalArgumentException("Invalid SSH file inventory")
            header = text
            return
        }
        if (head.endsWith('F')) {
            val tab = indexOf(bytes, TAB, from, to)
            require(tab > from) { "Invalid SSH file metadata" }
            sizes[decodePath(bytes, tab + 1, to) ?: throw IllegalArgumentException("Source name is not valid UTF-8")] = size(bytes, from, tab)
            return
        }
        require(to - from >= 3 && bytes[from + 1] == TAB) { "Invalid SSH file metadata" }
        val tab = indexOf(bytes, TAB, from + 2, to)
        require(tab > from + 2) { "Invalid SSH file metadata" }
        val fileSize = size(bytes, from + 2, tab)
        val path = decodePath(bytes, tab + 1, to) ?: return skip(bytes, tab + 1, to)
        when (bytes[from].toInt().toChar()) {
            'f', 'l' -> sizes[path] = fileSize
            'd' -> directories.add(path)
            else -> others.add(path)
        }
    }

    /** Skips an entry whose name cannot be passed to rsync and marks its directory as incompletely known. */
    private fun skip(bytes: ByteArray, from: Int, to: Int) {
        var componentStart = from
        var index = from
        while (index <= to) {
            if (index == to || bytes[index] == '/'.code.toByte()) {
                if (decodePath(bytes, componentStart, index) == null) break
                componentStart = index + 1
            }
            index++
        }
        val end = minOf(index, to)
        val parent = if (componentStart > from) bytes.decodeToString(from, componentStart - 1) else ""
        incomplete.add(parent)
        // Children of an undecodable directory share its key and are reported once.
        val key = escapedName(bytes, from, end)
        if (skippedKeys.add(key)) skipped += key
    }

    private companion object {
        const val TAB: Byte = 9
        fun indexOf(bytes: ByteArray, value: Byte, from: Int, to: Int): Int {
            for (index in from until to) if (bytes[index] == value) return index
            return -1
        }
        fun size(bytes: ByteArray, from: Int, to: Int): Long {
            require(to > from && to - from <= 19) { "Invalid SSH file size" }
            var value = 0L
            for (index in from until to) {
                val digit = bytes[index] - '0'.code.toByte()
                require(digit in 0..9) { "Invalid SSH file size" }
                value = value * 10 + digit
            }
            return value
        }
        fun decodePath(bytes: ByteArray, from: Int, to: Int): String? =
            try { bytes.decodeToString(from, to, throwOnInvalidSequence = true) } catch (_: CharacterCodingException) { null }
    }
}

/** Test and diagnostic helper: decodes complete text records. */
internal fun decodeInventory(records: List<String>): FileInventory = InventoryDecoder().apply {
    records.forEach { accept((it + '\u0000').encodeToByteArray()) }
}.finish().inventory

/** Displayable name: valid UTF-8 is kept, other bytes and control characters become \xHH. */
internal fun escapedName(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): String = buildString {
    fun hex(value: Int) { append("\\x").append(value.toString(16).uppercase().padStart(2, '0')) }
    var index = from
    while (index < to) {
        val lead = bytes[index].toInt() and 0xff
        val width = when (lead) { in 0..0x7f -> 1; in 0xc2..0xdf -> 2; in 0xe0..0xef -> 3; in 0xf0..0xf4 -> 4; else -> 0 }
        val text = if (width > 0 && index + width <= to)
            try { bytes.decodeToString(index, index + width, throwOnInvalidSequence = true) } catch (_: CharacterCodingException) { null }
        else null
        when {
            text == null -> { hex(lead); index++ }
            width == 1 && (lead < 0x20 || lead == 0x7f) -> { hex(lead); index++ }
            lead == '\\'.code -> { append("\\\\"); index++ }
            else -> { append(text); index += width }
        }
    }
}

internal suspend fun ProcessByteSource.readTail(limit: Int): String {
    var tail = ByteArray(0)
    val chunk = ByteArray(8192)
    while (true) {
        val count = read(chunk)
        if (count < 0) return tail.decodeToString()
        val retained = minOf(tail.size, (limit - count).coerceAtLeast(0))
        val incoming = minOf(count, limit)
        tail = ByteArray(retained + incoming).also {
            tail.copyInto(it, 0, tail.size - retained)
            chunk.copyInto(it, retained, count - incoming, count)
        }
    }
}
