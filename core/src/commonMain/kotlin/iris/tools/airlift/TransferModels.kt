package iris.tools.airlift

/** Paths are relative to the roots. sourceRoot optionally overrides only the source for this task.
 * Input order is preserved; id and destination path must be unique across the transfer.
 * fileTotal is the batch size; a monitor copy may carry it without the full file list. */
data class TransferTask(val path: String, val size: Long? = null, val id: String = path, val sourceRoot: String? = null,
    val files: List<String> = emptyList(), val entries: List<String> = emptyList(), val fileTotal: Int = files.size) {
    val fileCount: Int get() = fileTotal.coerceAtLeast(1)
    /** True for a multi-file job, including a monitor copy that carries only fileTotal. */
    val isBatch: Boolean get() = fileTotal > 0
    init {
        require(fileTotal >= 0 && (files.isEmpty() || fileTotal == files.size)) { "fileTotal must match the file list" }
        require(id.isNotBlank()) { "Task id must not be blank" }
        require(path.isNotEmpty() && !path.startsWith('/') && '\u0000' !in path && path.split('/').none { it == ".." || it == "." || it.isEmpty() }) {
            "Expected a relative POSIX file path without traversal: $path"
        }
        require(size == null || size >= 0)
        require(sourceRoot == null || (sourceRoot.isNotEmpty() && '\u0000' !in sourceRoot))
        files.forEach { require(it.isNotEmpty() && !it.startsWith('/') && '\u0000' !in it && it.split('/').none { part -> part in listOf("", ".", "..") }) }
        require(files.distinct().size == files.size)
        entries.forEach { entry ->
            val relative = entry.removeSuffix("/")
            require(entry == "." || (relative.isNotEmpty() && !relative.startsWith('/') && '\u0000' !in relative && relative.split('/').none { it in listOf("", ".", "..") }))
        }
    }
}

enum class TransferDirection { DOWNLOAD, UPLOAD }
enum class TaskStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
enum class WorkerStatus { CONNECTING, IDLE, TRANSFERRING, FAILED, STOPPED, FINISHED }

data class TransferProgress(
    val logicalBytes: Long, val bytesPerSecond: Double?, val remainingSeconds: Long?, val percent: Int? = null,
    val matchedBytes: Long? = null, val literalBytes: Long? = null,
    val currentFile: String? = null, val transferredFiles: Int? = null,
    /** 1-based position of currentFile in the submitted batch list, when known. */
    val currentFileIndex: Int? = null,
)
data class WorkerState(val id: Int, val status: WorkerStatus, val taskId: String? = null, val error: String? = null)
