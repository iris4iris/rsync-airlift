package iris.tools.airlift

import kotlinx.io.files.Path

/** Monotonic timestamps must use one origin for progress freshness and elapsed-time calculations. */
interface DispatcherClock {
    fun wallTimeMillis(): Long
    fun monotonicNanos(): Long
}

interface DispatcherLogStore {
    suspend fun createRunDirectory(): Path
    fun attemptPath(run: Path, worker: Int, taskIndex: Int, attempt: Int): Path
}

/** One transport per worker. close() must be idempotent and unblock connect/transfer. */
interface TransferSession {
    /** Diagnostic for the last transfer; reset before each attempt. */
    val failureDetails: String? get() = null
    suspend fun connect()
    suspend fun transfer(task: TransferTask, logFile: Path, progress: suspend (TransferProgress) -> Unit): Int
    /** Checked after a failed attempt; false returns the job to the queue and reconnects the worker. */
    suspend fun isConnected(): Boolean = true
    suspend fun close()
}

fun interface TransferSessionFactory {
    suspend fun create(workerId: Int): TransferSession
}

interface DispatcherPaths {
    fun absolute(path: Path): Path
}

internal expect fun platformLogs(config: AirliftConfig): DispatcherLogStore
internal expect fun platformClock(): DispatcherClock
internal expect fun platformPaths(): DispatcherPaths
internal expect fun platformWorkerDispatcher(): kotlinx.coroutines.CoroutineDispatcher
