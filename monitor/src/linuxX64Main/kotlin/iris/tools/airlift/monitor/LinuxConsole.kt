@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package iris.tools.airlift.monitor

import kotlinx.cinterop.*
import platform.posix.*
import iris.tools.airlift.monitor.posix.*

internal actual fun hasInteractiveConsole(): Boolean = isatty(STDIN_FILENO) == 1 && isatty(STDERR_FILENO) == 1
internal actual fun confirmationColorsEnabled(): Boolean = getenv("NO_COLOR") == null && getenv("TERM")?.toKString() != "dumb"
internal actual fun askTransferConfirmation(text: String, swing: Boolean): Boolean {
    require(!swing) { "Swing is only available in the JVM version" }
    check(hasInteractiveConsole()) { "Transfer confirmation requires a terminal. Use --yes for an unattended run." }
    println(emphasizeConfirmation(text))
    print("Start synchronization? [y/N]: "); fflush(stdout)
    return readlnOrNull()?.trim()?.lowercase() in setOf("y", "yes")
}
internal actual fun readConsolePassword(prompt: String): CharArray? = memScoped {
    // "e": O_CLOEXEC, so a child started meanwhile never inherits the terminal descriptor.
    val file = fopen("/dev/tty", "r+e") ?: return@memScoped null
    val fd = fileno(file)
    val original = alloc<termios>()
    if (tcgetattr(fd, original.ptr) != 0) { fclose(file); return@memScoped null }
    val flags = fcntl(fd, F_GETFL)
    if (flags < 0) { fclose(file); return@memScoped null }
    val signals = airlift_install_signals() ?: run { fclose(file); return@memScoped null }
    val hidden = alloc<termios>(); memcpy(hidden.ptr, original.ptr, sizeOf<termios>().toULong())
    hidden.c_lflag = hidden.c_lflag and ECHO.toUInt().inv()
    if (tcsetattr(fd, TCSAFLUSH, hidden.ptr) != 0) { airlift_restore_signals(signals); fclose(file); return@memScoped null }
    val bytes = mutableListOf<Byte>()
    try {
        if (fcntl(fd, F_SETFL, flags or O_NONBLOCK) < 0) linuxError("configure password input")
        fputs(prompt, file); fflush(file)
        val byte = alloc<ByteVar>()
        while (true) {
            if (airlift_stopping() != 0) return@memScoped null
            val count = read(fd, byte.ptr, 1u)
            if (count < 0 && errno == EINTR) continue
            // Wake on input, or every 100 ms to notice a stop signal.
            if (count < 0 && errno == EAGAIN) { airlift_wait_fd(fd, POLLIN, 100); continue }
            if (count <= 0) return@memScoped null
            val char = byte.value.toInt() and 255
            if (char == 10 || char == 13) break
            require(bytes.size < 65536) { "Password is too long" }
            bytes += char.toByte()
        }
        val raw = bytes.toByteArray()
        try { raw.decodeToString().toCharArray() } finally { raw.fill(0) }
    } finally {
        bytes.fill(0); fcntl(fd, F_SETFL, flags); tcsetattr(fd, TCSAFLUSH, original.ptr); airlift_restore_signals(signals); fputs("\n", file); fflush(file); fclose(file)
    }
}
