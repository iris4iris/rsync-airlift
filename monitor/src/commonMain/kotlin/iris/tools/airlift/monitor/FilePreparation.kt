package iris.tools.airlift.monitor

import iris.tools.airlift.*
import kotlinx.io.files.Path

/** Listing of one source. A depth-limited listing reports unvisited directories as entries without files.
 * incomplete: directories whose entries were skipped; skipped: displayable paths of those entries. */
internal data class FileInventory(val singleFile: Boolean, val sizes: Map<String, Long>,
    val directories: Set<String> = emptySet(), val otherEntries: Set<String> = emptySet(),
    val incomplete: Set<String> = emptySet(), val skipped: List<String> = emptyList(),
    val skippedCount: Int = skipped.size, val hint: String? = null)
internal data class PreparedTransfer(val config: AirliftConfig, val tasks: List<TransferTask>,
    val inventories: List<DirectoryInventory> = emptyList(), val warnings: List<String> = emptyList())

/** A source argument. An existing literal path wins; otherwise unescaped wildcards make it a mask below [base]. */
internal data class SourceQuery(val path: String, val base: String? = null, val mask: String? = null,
    val unescaped: String? = null, val maxDepth: Int? = null)
internal enum class SourceMatch { LITERAL, MASK, UNESCAPED }
internal data class SourceListing(val match: SourceMatch, val inventory: FileInventory)

private data class SourceSelection(val root: String, val directory: Boolean, val tasks: List<TransferTask>,
    val inventory: DirectoryInventory?, val warnings: List<String>)

private const val SHOWN_SKIPPED = 20
private const val BYTE_ORDER_MARK = "\uFEFF"

internal suspend fun prepareSources(config: AirliftConfig, roots: List<TransferRoots>, manifest: Path? = null, nulSeparated: Boolean = false,
    inventory: suspend (SourceQuery) -> SourceListing = { readSourceListing(config, it) }): PreparedTransfer {
    val entries = manifest?.let { readManifest(it, nulSeparated) }
    val download = config.direction == TransferDirection.DOWNLOAD
    if (roots.size == 1) {
        val source = if (download) roots.single().remoteRoot else localSource(roots.single().localRoot)
        val selection = selectSource(sourceQuery(source, entries == null), entries, inventory, allowMissing = false)
        return PreparedTransfer(rootedConfig(config, selection.root), selection.tasks, listOfNotNull(selection.inventory), selection.warnings)
    }
    val tasks = linkedMapOf<String, TransferTask>()
    val found = mutableSetOf<String>()
    val inventories = mutableListOf<DirectoryInventory>()
    val warnings = mutableListOf<String>()
    var firstRoot: String? = null
    for (root in roots) {
        val raw = if (download) root.remoteRoot else localSource(root.localRoot)
        if (download) config.copy(remoteRoot = raw) // validates every remote source like the first one
        val query = sourceQuery(raw, entries == null, absolute = if (download) null else { path -> absoluteLocal(path) })
        val selection = selectSource(query, entries, inventory, allowMissing = true)
        warnings += selection.warnings
        // A directory argument without a trailing slash (or "." / "..") retains its directory name, as in rsync.
        val name = selection.root.trimEnd('/').substringAfterLast('/')
        val named = selection.directory && !keepsContents(raw) && name.isNotEmpty() && name != "." && name != ".."
        val prefix = if (named) "$name/" else ""
        val sourceRoot = if (named) parentDirectory(selection.root) else trimRoot(selection.root)
        if (firstRoot == null) firstRoot = sourceRoot
        selection.inventory?.let { inventory ->
            fun mapped(paths: Set<String>) = if (prefix.isEmpty()) paths else paths.mapTo(HashSet(paths.size)) { (prefix + it).trimEnd('/') }
            inventories += DirectoryInventory(sourceRoot, prefix.trimEnd('/'), mapped(inventory.files), mapped(inventory.directories),
                mapped(inventory.otherEntries), mapped(inventory.incomplete))
        }
        for (task in selection.tasks) {
            found.add(task.path)
            val path = prefix + task.path
            val prepared = task.copy(path = path, id = path, sourceRoot = sourceRoot)
            val previous = tasks[path]
            require(previous == null || previous == prepared) { "Different sources target the same destination: $path" }
            tasks[path] = prepared
        }
    }
    if (entries != null) entries.firstOrNull { it !in found }?.let { throw IllegalArgumentException("--files entry was not found in any source: $it") }
    require(tasks.isNotEmpty()) { "No files to transfer: the sources contain no regular files or symlinks" }
    // Multiple sources: every task carries its own root; the configured root is the first source directory.
    return PreparedTransfer(rootedConfig(config, requireNotNull(firstRoot)),
        tasks.values.sortedWith(compareByDescending<TransferTask> { it.size }.thenBy { it.path }), inventories, warnings)
}

private fun rootedConfig(config: AirliftConfig, root: String) =
    if (config.direction == TransferDirection.DOWNLOAD) config.copy(remoteRoot = root) else config.copy(localRoot = Path(root))

/** Reads a --files list once: UTF-8, optional BOM, LF/CRLF or NUL separators, optional leading "./". */
internal suspend fun readManifest(manifest: Path, nulSeparated: Boolean): List<String> {
    val content = readManifestText(manifest).removePrefix(BYTE_ORDER_MARK)
    val lines = if (nulSeparated) content.split('\u0000') else content.split('\n').map { it.removeSuffix("\r") }
    return lines.asSequence().map { line ->
        var entry = line
        while (entry.startsWith("./")) entry = entry.substring(2)
        entry
    }.filter { it.isNotEmpty() }.onEach { entry ->
        try { TransferTask(entry) } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid --files entry '$entry': use a file path relative to the source directory, without '..'", e)
        }
    }.distinct().toList()
}

/** Discovery belongs to the CLI. The dispatcher still accepts a caller-prepared list. */
internal suspend fun prepareTransfer(
    config: AirliftConfig, manifest: Path? = null, nulSeparated: Boolean = false,
    source: String = if (config.direction == TransferDirection.DOWNLOAD) config.remoteRoot else localSource(config.localRoot.toString()),
    inventory: suspend (SourceQuery) -> SourceListing = { readSourceListing(config, it) },
    allowMissing: Boolean = false,
): PreparedTransfer {
    val entries = manifest?.let { readManifest(it, nulSeparated) }
    val selection = selectSource(sourceQuery(source, entries == null), entries, inventory, allowMissing)
    return PreparedTransfer(rootedConfig(config, selection.root), selection.tasks, listOfNotNull(selection.inventory), selection.warnings)
}

private suspend fun selectSource(query: SourceQuery, entries: List<String>?, inventory: suspend (SourceQuery) -> SourceListing,
    allowMissing: Boolean): SourceSelection {
    val listing = inventory(query)
    val files = listing.inventory
    val (path, mask) = when (listing.match) {
        SourceMatch.LITERAL -> query.path to null
        SourceMatch.UNESCAPED -> requireNotNull(query.unescaped) to null
        SourceMatch.MASK -> requireNotNull(query.base) to requireNotNull(query.mask)
    }
    require(mask == null || !files.singleFile) { "Mask base must be a directory: $path" }
    val root = if (files.singleFile) parentDirectory(path) else path
    val warnings = mutableListOf<String>()
    val selected = linkedSetOf<String>()
    if (entries == null || mask != null) {
        val matcher = mask?.let(::fileMask)
        files.sizes.keys.filterTo(selected) { matcher == null || matcher.matches(it) }
        if (matcher != null) {
            val directories = files.directories.count { it.isNotEmpty() && matcher.matches(it) }
            if (selected.isEmpty()) throw IllegalArgumentException("Mask matched no files: ${query.path}" +
                if (directories > 0) " (it matches $directories directories; end the mask with / or /** to select the files inside them)" else "")
            if (directories > 0) warnings += "Mask ${query.path} also matches $directories directories whose files are not selected; " +
                "end the mask with / or /** to include them."
        }
    }
    if (entries != null) selected.addAll(entries)
    val tasks = selected.filter { !allowMissing || it in files.sizes }.map { file ->
        TransferTask(file, files.sizes[file] ?: throw IllegalArgumentException("Source file is missing or is not a file: $file"))
    }.sortedWith(compareByDescending<TransferTask> { it.size }.thenBy { it.path })
    if (!allowMissing) require(tasks.isNotEmpty()) { "No files to transfer: ${query.path} contains no regular files or symlinks" }
    if (files.skippedCount > 0) warnings += skippedWarning(files, path)
    return SourceSelection(root, !files.singleFile && mask == null, tasks,
        if (files.singleFile) null else DirectoryInventory(null, "", files.sizes.keys, files.directories, files.otherEntries, files.incomplete),
        warnings)
}

private fun skippedWarning(files: FileInventory, root: String): String = buildString {
    append("Skipped ${files.skippedCount} entries whose names are not valid UTF-8 or cannot be read as names; ")
    append("they are not transferred and their directories are copied file by file:")
    files.skipped.take(SHOWN_SKIPPED).forEach { append("\n  ").append(root.trimEnd('/')).append('/').append(it) }
    if (files.skippedCount > SHOWN_SKIPPED) append("\n  ... and ${files.skippedCount - SHOWN_SKIPPED} more")
    files.hint?.let { append('\n').append(it) }
}

/** Local inventory: the literal path when it exists, otherwise the mask base or the unescaped path. */
internal suspend fun readSourceListing(config: AirliftConfig, query: SourceQuery): SourceListing {
    if (config.direction == TransferDirection.DOWNLOAD) return readInventory(config, query)
    if ((query.base == null && query.unescaped == null) || localEntryExists(query.path))
        return SourceListing(SourceMatch.LITERAL, readLocalInventory(query.path))
    query.base?.let { return SourceListing(SourceMatch.MASK, readLocalInventory(it, query.maxDepth)) }
    return SourceListing(SourceMatch.UNESCAPED, readLocalInventory(requireNotNull(query.unescaped)))
}

/** Windows separators become '/'; elsewhere a backslash is a file-name character or a mask escape. */
internal fun localSource(path: String): String = if (windowsLocalPaths) path.replace('\\', '/') else path

private val workingDirectory: String by lazy { localSource(Path(".").absolutePath(normalize = true).toString()) }

/** Lexical absolute path; it also works for masks, which are not valid file-system paths on every platform. */
internal fun absoluteLocal(path: String, cwd: () -> String = { workingDirectory }): String {
    val prefix = Regex("^(?:[A-Za-z]:/|//|/)").find(path)?.value
        ?: return absoluteLocal(cwd().trimEnd('/') + "/" + path) { "/" }
    val parts = mutableListOf<String>()
    for (part in path.substring(prefix.length).split('/')) when (part) {
        "", "." -> Unit
        ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
        else -> parts += part
    }
    val joined = prefix + parts.joinToString("/")
    return if (path.endsWith('/') && !joined.endsWith('/')) "$joined/" else joined
}

/** rsync semantics: a trailing slash, "." or ".." copies the contents rather than the named directory. */
private fun keepsContents(path: String) = path.endsWith('/') || path.substringAfterLast('/') in setOf(".", "..")

private fun trimRoot(path: String): String {
    val trimmed = path.trimEnd('/')
    return when {
        trimmed.isEmpty() -> "/"
        Regex("^[A-Za-z]:$").matches(trimmed) -> "$trimmed/"
        else -> trimmed
    }
}

private fun parentDirectory(path: String): String = trimRoot(path.trimEnd('/').substringBeforeLast('/', ".") + "/")

internal fun sourceQuery(source: String, depthLimited: Boolean = false, absolute: ((String) -> String)? = null): SourceQuery {
    fun resolved(path: String) = absolute?.invoke(path) ?: path
    val wildcard = firstWildcard(source)
    if (wildcard < 0) {
        val unescaped = unescapeMask(source)
        return SourceQuery(resolved(source), unescaped = resolved(unescaped).takeIf { unescaped != source })
    }
    val slash = source.lastIndexOf('/', wildcard)
    // The base always ends with '/', so a symlinked base directory is followed like rsync's "dir/".
    val base = if (slash < 0) "./" else unescapeMask(source.substring(0, slash + 1))
    val mask = source.substring(slash + 1).let { if (it.endsWith('/')) "$it**" else it }
    val depth = if (depthLimited && "**" !in mask) mask.count { it == '/' } + 1 else null
    return SourceQuery(resolved(source), resolved(base).let { if (it.endsWith('/')) it else "$it/" }, mask, maxDepth = depth)
}

/** Directory before the first unescaped wildcard, unescaped; the source itself when it is not a mask. */
internal fun sourceBase(source: String): String {
    val wildcard = firstWildcard(source)
    return if (wildcard >= 0) unescapeMask(source.substring(0, source.lastIndexOf('/', wildcard) + 1)).ifEmpty { "." } else source
}

/** Index of the first wildcard that is not escaped; an unclosed '[' is an ordinary character. */
internal fun firstWildcard(source: String): Int {
    var i = 0
    while (i < source.length) {
        when (source[i]) {
            '\\' -> i += 2
            '*', '?' -> return i
            '[' -> if (bracketEnd(source, i + 1) >= 0) return i else i++
            else -> i++
        }
    }
    return -1
}

internal fun unescapeMask(text: String): String {
    if ('\\' !in text) return text
    return buildString {
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            if (c == '\\' && i < text.length) append(text[i++]) else append(c)
        }
    }
}

/** POSIX path masks: * and ? stay in one component; ** crosses directories; [abc], [a-z], [!abc] classes; \ escapes. */
internal fun fileMask(mask: String): Regex {
    val result = StringBuilder("^")
    val literal = StringBuilder()
    fun flush() { if (literal.isNotEmpty()) { result.append(Regex.escape(literal.toString())); literal.setLength(0) } }
    var i = 0
    while (i < mask.length) {
        when (val c = mask[i++]) {
            '\\' -> literal.append(if (i < mask.length) mask[i++] else '\\')
            '*' -> {
                flush()
                if (i < mask.length && mask[i] == '*') {
                    i++
                    if (i < mask.length && mask[i] == '/') { i++; result.append("(?:.*/)?") } else result.append(".*")
                } else result.append("[^/]*")
            }
            '?' -> { flush(); result.append("[^/]") }
            '[' -> {
                val end = bracketEnd(mask, i)
                if (end < 0) literal.append(c) else { flush(); result.append(bracketClass(mask, i, end)); i = end + 1 }
            }
            else -> literal.append(c)
        }
    }
    flush()
    return Regex(result.append('$').toString())
}

/** Index of the ']' closing a class that starts at [start] (after '['), or -1. A leading ']' is a member. */
private fun bracketEnd(mask: String, start: Int): Int {
    var i = start
    if (i < mask.length && (mask[i] == '!' || mask[i] == '^')) i++
    if (i < mask.length && mask[i] == ']') i++
    while (i < mask.length) {
        when (mask[i]) {
            '\\' -> i += 2
            ']' -> return i
            else -> i++
        }
    }
    return -1
}

private fun bracketClass(mask: String, start: Int, end: Int): String {
    var i = start
    val negated = mask[i] == '!' || mask[i] == '^'
    if (negated) i++
    // A class never matches '/', which separates components.
    val result = StringBuilder(if (negated) "[^/" else "[")
    fun member(c: Char) = if (c.isLetterOrDigit() || c.isSurrogate()) c.toString() else "\\" + c
    fun next(): Char {
        val c = mask[i++]
        return if (c == '\\' && i < end) mask[i++] else c
    }
    while (i < end) {
        if (mask[i] == '[' && i + 1 < end && mask[i + 1] in ":=.")
            throw IllegalArgumentException("Character classes such as [:alpha:] are not supported in masks: $mask")
        val first = next()
        if (i + 1 < end && mask[i] == '-') {
            i++
            val last = next()
            require(first <= last) { "Invalid range $first-$last in mask: $mask" }
            require(negated || '/' !in first..last) { "A bracket class cannot match '/': $mask" }
            result.append(member(first)).append('-').append(member(last))
        } else {
            require(negated || first != '/') { "A bracket class cannot match '/': $mask" }
            result.append(member(first))
        }
    }
    return result.append(']').toString()
}
