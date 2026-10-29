package iris.tools.airlift

import kotlinx.io.files.Path

data class AirliftConfig(
    val host: String,
    val remoteRoot: String,
    val localRoot: Path,
    val workers: Int = 16,
    val direction: TransferDirection = TransferDirection.DOWNLOAD,
    val port: Int = 22,
    val identityFile: Path? = null,
    val maxAttempts: Int = 1,
    val retryDelayMillis: Long = 1000,
    val connectTimeoutSeconds: Int = 15,
    val logDirectory: Path = Path("airlift-logs"),
    val sshExecutable: String = "ssh",
    val rsyncExecutable: String = "rsync",
    val rsyncOptions: String? = null,
    /** Extra OpenSSH client options (for example a cipher); Airlift's own transport options take precedence. */
    val sshOptions: String? = null,
    /** SSH connection attempts per worker, including reconnects after a lost ControlMaster. */
    val connectAttempts: Int = 3,
    /** Size of each start-up connection wave and the limit for concurrent SSH handshakes, including reconnects. */
    val connectConcurrency: Int = 5,
) {
    init {
        require(host.isNotBlank() && !host.startsWith('-') && host.none { it.isWhitespace() || it.isISOControl() })
        require(remoteRoot.startsWith('/') && remoteRoot.none { it.isISOControl() })
        require(workers in 1..MAX_WORKERS) { "Workers must be between 1 and $MAX_WORKERS" }
        require(maxAttempts > 0 && retryDelayMillis >= 0 && connectTimeoutSeconds > 0)
        require(connectAttempts > 0 && connectConcurrency > 0)
        require(port in 1..65535)
        rsyncOptions?.let(RsyncArguments::parse)
        sshOptions?.let(SshOptions::parse)
    }

    /** Validated extra OpenSSH arguments; every SSH invocation for this transfer appends them. */
    val sshArguments: List<String> get() = sshOptions?.let(SshOptions::parse).orEmpty()

    companion object {
        /** Upper bound for the worker pool; each worker owns an SSH connection and rsync processes. */
        const val MAX_WORKERS = 256
    }
}

data class TaskState(
    val task: TransferTask,
    val status: TaskStatus = TaskStatus.QUEUED,
    val attempts: Int = 0,
    val progress: TransferProgress? = null,
    val error: String? = null,
    val logFile: Path? = null,
    val progressUpdatedAtNanos: Long? = null,
    /** Non-fatal note for a successful job, for example rsync code 24 (source files vanished). */
    val warning: String? = null,
)

data class AirliftSnapshot(
    val tasks: List<TaskState>, val workers: List<WorkerState>, val paused: Boolean, val cancelled: Boolean,
    val targetWorkers: Int = 0, val startedAtMillis: Long? = null, val finishedAtMillis: Long? = null,
) {
    val queued: Int get() = tasks.filter { it.status == TaskStatus.QUEUED }.sumOf { it.task.fileCount }
    val completed: Int get() = tasks.filter { it.status == TaskStatus.SUCCEEDED }.sumOf { it.task.fileCount }
    val failed: Int get() = tasks.filter { it.status == TaskStatus.FAILED }.sumOf { it.task.fileCount }
}
