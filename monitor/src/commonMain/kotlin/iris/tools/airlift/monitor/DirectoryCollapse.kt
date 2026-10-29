package iris.tools.airlift.monitor

import iris.tools.airlift.ArgumentText
import iris.tools.airlift.RsyncArguments
import iris.tools.airlift.TransferTask

private fun parents(path: String): List<String> {
    val result = mutableListOf<String>()
    var parent = path.substringBeforeLast('/', "")
    while (true) {
        result.add(parent)
        if (parent.isEmpty()) return result
        parent = parent.substringBeforeLast('/', "")
    }
}

/** Rsync options under which a recursive directory entry could copy, delete or skip more than the listed files. */
internal object CollapsePolicy {
    // Short options whose value is the rest of the cluster or the next argument.
    private const val SHORT_WITH_VALUE = "BeTfM@"
    // -L/-k/-K follow links; -f/-F/-C add filters; -x stops recursion at mount points.
    private const val DISABLING_SHORT = "LkKfFCx"
    private val disablingLong = setOf("--del", "--remove-source-files", "--remove-sent-files", "--copy-links", "--copy-dirlinks",
        "--keep-dirlinks", "--copy-unsafe-links", "--no-recursive", "--no-r", "--exclude", "--exclude-from", "--include",
        "--include-from", "--filter", "--cvs-exclude", "--one-file-system")
    // Long options whose value may follow as a separate argument; values are never scanned as options.
    private val longWithValue = setOf("--exclude", "--include", "--exclude-from", "--include-from", "--filter", "--suffix",
        "--partial-dir", "--temp-dir", "--compare-dest", "--copy-dest", "--link-dest", "--backup-dir", "--bwlimit", "--max-size",
        "--min-size", "--max-delete", "--chmod", "--chown", "--usermap", "--groupmap", "--log-file", "--log-file-format",
        "--out-format", "--password-file", "--timeout", "--contimeout", "--iconv", "--info", "--debug", "--skip-compress",
        "--modify-window", "--block-size", "--checksum-choice", "--cc", "--compress-choice", "--zc", "--compress-level", "--zl",
        "--protocol", "--port", "--sockopts", "--outbuf", "--remote-option", "--write-batch", "--read-batch",
        "--only-write-batch", "--checksum-seed", "--rsync-path", "--address", "--stop-after", "--stop-at", "--early-input",
        "--max-alloc", "--copy-as", "--time-limit", "--stderr")

    fun allows(options: String?): Boolean {
        if (options.isNullOrBlank()) return true
        RsyncArguments.parse(options)
        val tokens = ArgumentText.split(options, "--rsync")
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i++]
            if (token.startsWith("--")) {
                val name = token.substringBefore('=')
                if (name.startsWith("--delete") || name in disablingLong) return false
                if ('=' !in token && name in longWithValue) i++
            } else if (token.startsWith('-')) {
                for (index in 1 until token.length) {
                    val option = token[index]
                    if (option in DISABLING_SHORT) return false
                    if (option in SHORT_WITH_VALUE) { if (index == token.lastIndex) i++; break }
                }
            }
        }
        return true
    }
}

/** Complete inventories prove coverage; selected files alone are never enough. */
internal class DirectoryCollapser(inventories: List<DirectoryInventory>, rsyncOptions: String? = null) {
    /** All inventories of one source root, merged: a directory is provable when it lies within one of their scopes. */
    private class Coverage(val files: Set<String>, val counts: Map<String, Int>, val blocked: Set<String>, val scopes: Set<String>)
    private val enabled = CollapsePolicy.allows(rsyncOptions)
    private val coverage: Map<String?, Coverage> = if (!enabled) emptyMap() else inventories.groupBy { it.sourceRoot }.mapValues { (_, group) ->
        val files = if (group.size == 1) group.single().files else group.flatMapTo(HashSet()) { it.files }
        val counts = HashMap<String, Int>()
        files.forEach { file -> parents(file).forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        val blocked = HashSet<String>()
        fun block(entry: String) { if (blocked.add(entry)) parents(entry).forEach { blocked.add(it) } }
        for (inventory in group) {
            // Avoid introducing unselected empty or unvisited (depth-limited) directories, special entries and skipped names.
            // Emptiness is judged per inventory: another inventory's files must not hide what this listing did not visit.
            val nonEmpty: Set<String> = if (group.size == 1) counts.keys else inventory.files.flatMapTo(HashSet()) { parents(it) }
            inventory.otherEntries.forEach(::block)
            inventory.directories.forEach { if (it.isNotEmpty() && it !in nonEmpty) block(it) }
            inventory.incomplete.forEach(::block)
        }
        Coverage(files, counts, blocked, group.mapTo(HashSet()) { it.scope })
    }

    fun optimize(job: TransferTask): TransferTask {
        if (!enabled) return job
        val files = job.files.ifEmpty { listOf(job.path) }
        if (files.size < 2) return job
        val cover = coverage[job.sourceRoot] ?: return job
        val selectedCounts = HashMap<String, Int>()
        val coveredCounts = HashMap<String, Int>()
        for (file in files) {
            val covered = file in cover.files
            parents(file).forEach { directory ->
                selectedCounts[directory] = (selectedCounts[directory] ?: 0) + 1
                if (covered) coveredCounts[directory] = (coveredCounts[directory] ?: 0) + 1
            }
        }
        fun provable(directory: String): Boolean {
            if ("" in cover.scopes || directory in cover.scopes) return true
            var parent = directory
            while (parent.isNotEmpty()) {
                parent = parent.substringBeforeLast('/', "")
                if (parent in cover.scopes) return true
            }
            return false
        }
        val candidates = selectedCounts.entries.filter { (directory, count) ->
            count > 1 && directory !in cover.blocked && cover.counts[directory] == count && coveredCounts[directory] == count && provable(directory)
        }.map { it.key }
        val chosen = mutableSetOf<String>()
        // Shallowest first, so a chosen directory absorbs its candidate descendants.
        for (directory in candidates.sortedWith(compareBy<String> { if (it.isEmpty()) 0 else it.count { c -> c == '/' } + 1 }.thenBy { it })) {
            if (directory.isEmpty() || parents(directory).none { it in chosen }) chosen.add(directory)
        }
        if (chosen.isEmpty()) return job
        val entries = mutableListOf<String>()
        val emitted = mutableSetOf<String>()
        for (file in files.sorted()) {
            val directory = parents(file).firstOrNull { it in chosen }
            if (directory == null) entries.add(file)
            else if (emitted.add(directory)) entries.add(if (directory.isEmpty()) "." else "$directory/")
        }
        return if (entries.size < files.size) job.copy(entries = entries) else job
    }
}
