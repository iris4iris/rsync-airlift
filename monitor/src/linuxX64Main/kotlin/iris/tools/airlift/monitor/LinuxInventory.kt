@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package iris.tools.airlift.monitor

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.io.files.Path
import platform.posix.*

internal actual val windowsLocalPaths: Boolean = false

internal actual fun localEntryExists(path: String): Boolean = memScoped { lstat(path, alloc<stat>().ptr) == 0 }

internal actual suspend fun readLocalInventory(source: String, maxDepth: Int?): FileInventory = withContext(preparationDispatcher()) {
    // "dir/" names the directory a symlink points to, as in rsync; without the slash the link itself is the source.
    val follow = source.endsWith('/') || source.substringAfterLast('/') in setOf(".", "..")
    val root = Path(source).absolutePath(normalize = true).toString()
    val sizes = linkedMapOf<String, Long>()
    val directories = linkedSetOf<String>()
    val others = linkedSetOf<String>()
    val incomplete = linkedSetOf<String>()
    val skipped = mutableListOf<String>()
    /** File type and size, or null when the entry vanished. */
    fun metadata(path: String, followLink: Boolean): Pair<Int, Long>? = memScoped {
        val info = alloc<stat>()
        if ((if (followLink) stat(path, info.ptr) else lstat(path, info.ptr)) != 0) {
            if (errno == ENOENT) return null
            linuxError("lstat $path")
        }
        (info.st_mode.toInt() and S_IFMT) to info.st_size
    }
    fun child(parent: String, name: String) = if (parent.endsWith('/')) parent + name else "$parent/$name"
    val (mode, size) = metadata(root, follow) ?: throw IllegalArgumentException("Source does not exist: $source")
    if (mode != S_IFDIR) {
        require(!follow) { "Not a directory: $source" }
        require(mode == S_IFREG || mode == S_IFLNK) { "Not a regular file, symlink or directory: $source" }
        return@withContext FileInventory(true, mapOf(Path(root).name to size))
    }
    suspend fun visitDirectory(path: String, relative: String, depth: Int, noFollow: Int) {
        directories += relative
        if (maxDepth != null && depth >= maxDepth) return
        val descriptor = open(path, O_RDONLY or O_DIRECTORY or O_CLOEXEC or noFollow)
        if (descriptor < 0) {
            if (errno == ENOENT && relative.isNotEmpty()) return
            linuxError("open directory $path")
        }
        val directory = fdopendir(descriptor) ?: run { close(descriptor); linuxError("fdopendir $path") }
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                iris.tools.airlift.monitor.posix.airlift_clear_errno()
                val entry = readdir(directory)
                if (entry == null) { if (errno != 0) linuxError("readdir $path"); break }
                // Raw name bytes: toKString() would replace invalid UTF-8 and the entry could not be opened again.
                val pointer = entry.pointed.d_name
                var nameLength = 0
                while (pointer[nameLength] != 0.toByte()) nameLength++
                val bytes = pointer.readBytes(nameLength)
                if (bytes.size <= 2 && bytes.all { it == '.'.code.toByte() }) continue
                val name = try { bytes.decodeToString(throwOnInvalidSequence = true) } catch (_: CharacterCodingException) {
                    // rsync file lists are UTF-8 text here; such an entry is reported and its directory never collapses.
                    incomplete += relative
                    skipped += escapedName(if (relative.isEmpty()) bytes else (relative + "/").encodeToByteArray() + bytes)
                    continue
                }
                val childPath = child(path, name)
                val childRelative = if (relative.isEmpty()) name else "$relative/$name"
                // An entry that vanished after readdir is simply absent from the initial inventory.
                val (type, length) = metadata(childPath, false) ?: continue
                when (type) {
                    S_IFREG, S_IFLNK -> sizes[childRelative] = length
                    S_IFDIR -> visitDirectory(childPath, childRelative, depth + 1, O_NOFOLLOW)
                    else -> others += childRelative
                }
            }
        } finally { closedir(directory) }
    }
    visitDirectory(root, "", 0, if (follow) 0 else O_NOFOLLOW)
    FileInventory(false, sizes, directories, others, incomplete, skipped.take(1000), skipped.size)
}
