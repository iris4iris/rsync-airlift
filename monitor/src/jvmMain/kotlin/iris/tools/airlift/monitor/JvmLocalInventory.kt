package iris.tools.airlift.monitor

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.io.files.Path

internal actual val windowsLocalPaths: Boolean = java.io.File.separatorChar == '\\'

internal actual fun localEntryExists(path: String): Boolean = try {
    Files.exists(java.nio.file.Path.of(path), NOFOLLOW_LINKS)
} catch (_: InvalidPathException) { false }

/** JVM-only inventory preserves lstat sizes and never descends into directory symlinks below the root. */
internal actual suspend fun readLocalInventory(source: String, maxDepth: Int?): FileInventory = kotlinx.coroutines.withContext(preparationDispatcher()) {
    val follow = source.endsWith('/') || (windowsLocalPaths && source.endsWith('\\')) ||
        source.substringAfterLast('/') in setOf(".", "..")
    val normalized = Path(source).absolutePath(normalize = true).toNioPath()
    val (root, attributes) = try {
        // "dir/" names the directory a symlink points to, as in rsync; without the slash the link itself is the source.
        if (follow) normalized.toRealPath().let { it to Files.readAttributes(it, BasicFileAttributes::class.java) }
        else normalized to Files.readAttributes(normalized, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) { throw IllegalArgumentException("Source does not exist: $source") }
    if (!attributes.isDirectory) {
        require(!follow) { "Not a directory: $source" }
        require(attributes.isRegularFile || attributes.isSymbolicLink) { "Not a regular file, symlink or directory: $source" }
        return@withContext FileInventory(true, mapOf(root.fileName.toString() to attributes.size()))
    }
    val sizes = linkedMapOf<String, Long>()
    val directories = linkedSetOf<String>()
    val others = linkedSetOf<String>()
    val incomplete = linkedSetOf<String>()
    val skipped = mutableListOf<String>()
    val rootUri = root.toUri().rawPath.trimEnd('/') + "/"
    fun relative(path: java.nio.file.Path): String? {
        val text = root.relativize(path).toString().let { if (windowsLocalPaths) it.replace('\\', '/') else it }
        // The JVM decodes file names with sun.jnu.encoding; undecodable bytes become U+FFFD and cannot be opened again.
        if (windowsLocalPaths || '\uFFFD' !in text) return text
        incomplete += text.substringBeforeLast('/', "").takeUnless { '\uFFFD' in it } ?: ""
        val bytes = percentDecoded(path.toUri().rawPath.trimEnd('/').removePrefix(rootUri))
        skipped += escapedName(bytes)
        return null
    }
    Files.walkFileTree(root, emptySet(), maxDepth ?: Int.MAX_VALUE, object : SimpleFileVisitor<java.nio.file.Path>() {
        override fun preVisitDirectory(dir: java.nio.file.Path, attrs: BasicFileAttributes): FileVisitResult {
            directories += relative(dir) ?: return FileVisitResult.SKIP_SUBTREE
            return FileVisitResult.CONTINUE
        }
        override fun visitFile(file: java.nio.file.Path, attrs: BasicFileAttributes): FileVisitResult {
            val path = relative(file) ?: return FileVisitResult.CONTINUE
            when {
                attrs.isRegularFile || attrs.isSymbolicLink -> sizes[path] = attrs.size()
                attrs.isDirectory -> directories += path // at maxDepth: listed, not visited
                else -> others += path
            }
            return FileVisitResult.CONTINUE
        }
        override fun visitFileFailed(file: java.nio.file.Path, exc: IOException): FileVisitResult {
            // An entry that vanished after its directory was read is simply absent from the initial inventory.
            if (exc is NoSuchFileException && file != root) return FileVisitResult.CONTINUE
            throw IllegalStateException("Cannot read source entry $file: ${exc.message ?: exc::class.simpleName}", exc)
        }
        override fun postVisitDirectory(dir: java.nio.file.Path, exc: IOException?): FileVisitResult {
            if (exc != null && exc !is NoSuchFileException)
                throw IllegalStateException("Cannot read source directory $dir: ${exc.message ?: exc::class.simpleName}", exc)
            return FileVisitResult.CONTINUE
        }
    })
    val hint = System.getProperty("sun.jnu.encoding")?.takeIf { skipped.isNotEmpty() && !it.equals("UTF-8", ignoreCase = true) }?.let {
        "Java reads file names as $it; start Airlift in a UTF-8 locale (for example LC_ALL=C.UTF-8) to include non-ASCII names."
    }
    FileInventory(false, sizes, directories, others, incomplete, skipped.take(1000), skipped.size, hint)
}

private fun percentDecoded(text: String): ByteArray {
    val out = java.io.ByteArrayOutputStream(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '%' && i + 2 <= text.lastIndex) {
            out.write(text.substring(i + 1, i + 3).toInt(16)); i += 3
        } else { out.write(c.toString().toByteArray(Charsets.UTF_8)); i++ }
    }
    return out.toByteArray()
}
