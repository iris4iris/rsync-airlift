package iris.tools.airlift

/** Bounded diagnostic records from rsync's merged output; raw output stays in the log. */
internal class RsyncDiagnostics {
    private val records = ArrayDeque<String>()
    private var firstCause: String? = null

    fun accept(record: String) {
        val line = RsyncText.decode(record.replace(ansi, "")).filter { it >= ' ' }.trim().take(1024)
        if (line.isEmpty() || line.startsWith("AIRLIFT_FILE:") || progress.containsMatchIn(line)) return
        if (firstCause == null && isPrimary(line)) firstCause = line
        if (records.size == 16) records.removeFirst()
        records.addLast(line)
    }

    fun summary(): String? {
        // Prefer the original shell/SSH/file error to rsync's generic closing summary.
        return (firstCause ?: records.firstOrNull { it.startsWith("rsync error:") }
            ?: records.lastOrNull { !statistics.containsMatchIn(it) })?.take(600)
    }

    private fun isPrimary(line: String): Boolean =
        !line.startsWith("rsync error:") && !line.startsWith("rsync: connection unexpectedly closed") &&
            (line.startsWith("rsync:") || line.startsWith("ssh:") || line.startsWith("bash:") ||
                line.startsWith("sh:") || line.contains("not found", ignoreCase = true) ||
                line.contains("Permission denied", ignoreCase = true) ||
                line.contains("No such file", ignoreCase = true) ||
                line.contains("Host key verification failed", ignoreCase = true))

    private companion object {
        val ansi = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
        val progress = Regex("^\\s*[0-9,.]+\\s+[0-9]+%")
        val statistics = Regex("^(Number of |Total |Literal data:|Matched data:|File list |sent |received |total size is |sending |receiving )")
    }
}
