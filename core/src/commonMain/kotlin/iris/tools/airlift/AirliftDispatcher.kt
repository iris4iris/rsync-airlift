package iris.tools.airlift

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.io.files.Path

/** Platform-independent, single-use dispatcher. Call close() to cancel its owned scope. */
class AirliftDispatcher(
    private val config: AirliftConfig,
    tasks: List<TransferTask>,
    private val transports: TransferSessionFactory = SshSessionFactory(config),
    private val logs: DispatcherLogStore = platformLogs(config),
    private val clock: DispatcherClock = platformClock(),
    dispatcher: CoroutineDispatcher = platformWorkerDispatcher(),
    paths: DispatcherPaths = platformPaths(),
) {
    private val localDescription = paths.absolute(config.localRoot).toString()
    val sourceDescription: String get() = if (config.direction == TransferDirection.DOWNLOAD)
        "${config.host}:${config.remoteRoot}" else localDescription
    val destinationDescription: String get() = if (config.direction == TransferDirection.DOWNLOAD)
        localDescription else "${config.host}:${config.remoteRoot}"

    private val mutex = Mutex()
    // Start-up waves bound the first logins; this also bounds retries and reconnects, for example
    // when every worker loses its master in the same network outage.
    private val connectPermits = Semaphore(config.connectConcurrency)
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + dispatcher)
    private val wakeup = MutableStateFlow(0L)
    private val states = tasks.associate { it.id to TaskState(it) }.toMutableMap()
    private val taskIndexes = tasks.mapIndexed { index, task -> task.id to index }.toMap()
    private lateinit var runDirectory: Path
    private val queue = ArrayDeque(tasks.map { it.id })
    private val workers = linkedMapOf<Int, WorkerState>()
    private val activeTransports = mutableMapOf<Int, TransferSession>()
    private class ConnectionWave(val previous: Deferred<Unit>, var remaining: Int) {
        val completed = CompletableDeferred<Unit>()
    }
    private var connectionBarrier = CompletableDeferred<Unit>().apply { complete(Unit) }
    private val retiring = mutableSetOf<Int>()
    private val completion = CompletableDeferred<AirliftSnapshot>()
    private var finalSnapshot: AirliftSnapshot? = null
    private var started = false
    private var paused = false
    private var cancelled = false
    private var targetWorkers = config.workers
    private var nextWorker = 0
    private var liveWorkers = 0
    private var startedAtMillis: Long? = null
    private var finishedAtMillis: Long? = null

    init { require(states.size == tasks.size) { "Task ids must be unique" } }

    suspend fun snapshot(): AirliftSnapshot = mutex.withLock { snapshotLocked() }
    private fun snapshotLocked() = AirliftSnapshot(states.values.toList(), workers.values.toList(), paused, cancelled,
        targetWorkers, startedAtMillis, finishedAtMillis)

    private fun finishLocked(): AirliftSnapshot = finalSnapshot ?: run {
        finishedAtMillis = clock.wallTimeMillis()
        snapshotLocked().also { finalSnapshot = it }
    }

    // Never notify completion observers under the non-reentrant mutex.
    private fun publishCompletion(final: AirliftSnapshot?) {
        if (final != null) {
            completion.complete(final)
            supervisor.complete()
        }
    }

    suspend fun start() {
        mutex.withLock {
            check(!cancelled && !started) { "Dispatcher is closed or already started" }
            started = true
        }
        try {
            val directory = logs.createRunDirectory()
            val final = mutex.withLock {
                check(!cancelled) { "Dispatcher was closed during startup" }
                runDirectory = directory
                startedAtMillis = clock.wallTimeMillis()
                if (queue.isEmpty()) finishLocked() else { replenishLocked(); null }
            }
            publishCompletion(final)
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    /** Cancelling an awaiting coroutine does not cancel the run. */
    suspend fun awaitCompletion(): AirliftSnapshot = completion.await()
    suspend fun pause() = mutex.withLock { paused = true }
    suspend fun resume() = mutex.withLock { paused = false; signalLocked() }
    private fun signalLocked() { wakeup.value++ }

    suspend fun setWorkerCount(count: Int) = mutex.withLock {
        require(count in 1..AirliftConfig.MAX_WORKERS) { "Workers must be between 1 and ${AirliftConfig.MAX_WORKERS}" }
        check(!cancelled && finalSnapshot == null)
        targetWorkers = count
        if (started && this::runDirectory.isInitialized) replenishLocked()
        signalLocked()
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun replenishLocked() {
        while (!cancelled && queue.isNotEmpty() && liveWorkers - retiring.size < targetWorkers) {
            // A full wave must finish its connection attempts before the next one
            // starts. A semaphore alone would refill slots after each fast connect.
            val count = minOf(config.connectConcurrency, targetWorkers - (liveWorkers - retiring.size))
            val wave = ConnectionWave(connectionBarrier, count)
            connectionBarrier = wave.completed
            repeat(count) {
                val id = ++nextWorker
                liveWorkers++
                workers[id] = WorkerState(id, WorkerStatus.CONNECTING)
                // Cleanup also releases the wave if shutdown wins before dispatch.
                scope.launch(CoroutineName("airlift-worker-$id"), start = CoroutineStart.ATOMIC) { runWorker(id, wave) }
            }
        }
    }

    private suspend fun takeTask(id: Int): TransferTask? {
        while (true) {
            val observed = mutex.withLock {
                if (cancelled) return null
                if (liveWorkers - retiring.size > targetWorkers) {
                    retiring.add(id) // Reserve retirement before asynchronous resource cleanup.
                    return null
                }
                if (queue.isEmpty()) return null
                if (!paused) {
                    val taskId = queue.removeFirst()
                    val state = states.getValue(taskId)
                    states[taskId] = state.copy(status = TaskStatus.RUNNING)
                    workers[id] = WorkerState(id, WorkerStatus.TRANSFERRING, taskId)
                    return state.task
                }
                workers[id] = WorkerState(id, WorkerStatus.IDLE)
                wakeup.value
            }
            wakeup.first { it != observed }
        }
    }

    private enum class WorkerOutcome { FINISHED, LOST, LOST_AFTER_PROGRESS }

    private fun backoffMillis(failures: Int): Long =
        (config.retryDelayMillis * (1L shl (failures - 1).coerceIn(0, 5))).coerceAtMost(30_000)

    private suspend fun runWorker(id: Int, wave: ConnectionWave) {
        var transport: TransferSession? = null
        var fatal: String? = null
        // Only the first connection attempt belongs to the start-up wave; retries and reconnects
        // wait for the connection semaphore instead, so a backoff never holds up later waves.
        var pendingWave: ConnectionWave? = wave
        suspend fun leaveWave() {
            val current = pendingWave ?: return
            pendingWave = null
            // Failures/cancellation must not strand later waves. Notify outside
            // the state mutex, since completion can resume another worker.
            withContext(NonCancellable) {
                val release = mutex.withLock { --current.remaining == 0 }
                if (release) current.completed.complete(Unit)
            }
        }
        try {
            wave.previous.await()
            currentCoroutineContext().ensureActive()
            // Consecutive connection failures; a completed job resets the count.
            var failures = 0
            var lastError: String? = null
            while (true) {
                val session = transports.create(id)
                transport = session
                val registered = mutex.withLock {
                    if (!cancelled) {
                        activeTransports[id] = session
                        workers[id] = WorkerState(id, WorkerStatus.CONNECTING, workers[id]?.taskId, workers[id]?.error)
                    }
                    !cancelled
                }
                if (!registered) return
                val connected = try {
                    connectPermits.withPermit { session.connect() }
                    true
                } catch (e: CancellationException) {
                    // A transport-local timeout is a connection failure, not cancellation of the run.
                    currentCoroutineContext().ensureActive()
                    lastError = e.message ?: "SSH connection timed out"
                    false
                } catch (e: Exception) {
                    lastError = e.message ?: "SSH connection failed"
                    false
                } finally {
                    leaveWave()
                }
                val outcome = if (connected) transferLoop(id, session) else null
                if (outcome == WorkerOutcome.FINISHED) break
                if (outcome != null) lastError = "SSH connection lost"
                failures = if (outcome == WorkerOutcome.LOST_AFTER_PROGRESS) 1 else failures + 1
                withContext(NonCancellable) {
                    runCatching { session.close() }
                    mutex.withLock { activeTransports.remove(id) }
                }
                transport = null
                if (failures >= config.connectAttempts) { fatal = lastError; break }
                val stop = mutex.withLock {
                    workers[id] = WorkerState(id, WorkerStatus.CONNECTING, workers[id]?.taskId,
                        "$lastError; reconnecting (${failures + 1}/${config.connectAttempts})")
                    cancelled || queue.isEmpty()
                }
                if (stop) break
                delay(backoffMillis(failures))
            }
        } catch (e: CancellationException) {
            val stopping = withContext(NonCancellable) { mutex.withLock { cancelled } }
            if (stopping) throw e
            fatal = e.message ?: "Worker operation cancelled"
        } catch (e: Exception) {
            fatal = e.message ?: "Worker failed"
        } finally {
            // Shutdown before the first connection attempt must still release the wave.
            leaveWave()
            // Cancellation must not skip acquiring the mutex and publishing final state.
            withContext(NonCancellable) {
                runCatching { transport?.close() }
                val final = mutex.withLock {
                    activeTransports.remove(id)
                    val retired = retiring.remove(id)
                    val status = when {
                        cancelled -> WorkerStatus.STOPPED
                        fatal != null -> WorkerStatus.FAILED
                        retired -> WorkerStatus.STOPPED
                        queue.isEmpty() -> WorkerStatus.FINISHED
                        else -> WorkerStatus.STOPPED
                    }
                    workers[id] = WorkerState(id, status, workers[id]?.taskId, fatal)
                    liveWorkers--
                    if (fatal == null) replenishLocked()
                    val completed = if (liveWorkers == 0) {
                        for ((key, state) in states.toMap()) {
                            if (state.status == TaskStatus.QUEUED || state.status == TaskStatus.RUNNING) {
                                states[key] = state.copy(status = if (cancelled) TaskStatus.CANCELLED else TaskStatus.FAILED,
                                    error = if (cancelled) state.error else fatal ?: "No workers available")
                            }
                        }
                        queue.clear()
                        finishLocked()
                    } else null
                    signalLocked()
                    completed
                }
                publishCompletion(final)
            }
        }
    }

    /** Runs jobs over one connection. A job that fails because the connection died goes back to the queue. */
    private suspend fun transferLoop(id: Int, transport: TransferSession): WorkerOutcome {
        var completedJobs = 0
        while (true) {
            val task = takeTask(id) ?: return WorkerOutcome.FINISHED
            val taskStartedAt = clock.monotonicNanos()
            // Attempt numbers continue after a requeue so earlier attempt logs are kept.
            val previousAttempts = mutex.withLock { states.getValue(task.id).attempts }
            var error: String? = null
            var warning: String? = null
            var success = false
            var lost = false
            for (attempt in 1..config.maxAttempts) {
                if (mutex.withLock { cancelled }) break
                val number = previousAttempts + attempt
                val log = logs.attemptPath(runDirectory, id, taskIndexes.getValue(task.id), number)
                mutex.withLock {
                    states[task.id] = states.getValue(task.id).copy(attempts = number, progress = null,
                        logFile = log, progressUpdatedAtNanos = null)
                }
                try {
                    val code = transport.transfer(task, log) { progress ->
                        mutex.withLock {
                            states[task.id] = states.getValue(task.id).copy(progress = progress,
                                progressUpdatedAtNanos = clock.monotonicNanos())
                        }
                    }
                    success = code == 0 || code == VANISHED_SOURCE_FILES
                    warning = if (code == VANISHED_SOURCE_FILES) VANISHED_WARNING else null
                    error = if (success) null else failureMessage(transport.failureDetails, code)
                } catch (e: CancellationException) {
                    // A transport-local timeout is an attempt failure, not cancellation of the run.
                    currentCoroutineContext().ensureActive()
                    error = e.message ?: "Transfer cancelled"
                } catch (e: Exception) {
                    error = e.message ?: "Transfer failed"
                }
                if (success) break
                // A dead ControlMaster fails every later job in milliseconds; stop before draining the queue.
                if (!connectionAlive(transport)) { lost = true; break }
                if (attempt < config.maxAttempts) delay(config.retryDelayMillis)
            }
            if (lost) {
                val requeued = mutex.withLock {
                    if (!cancelled) {
                        states[task.id] = states.getValue(task.id).copy(status = TaskStatus.QUEUED, progress = null,
                            progressUpdatedAtNanos = null)
                        queue.addFirst(task.id)
                        workers[id] = WorkerState(id, WorkerStatus.CONNECTING, task.id, "SSH connection lost")
                        signalLocked()
                    }
                    !cancelled
                }
                if (requeued) return if (completedJobs > 0) WorkerOutcome.LOST_AFTER_PROGRESS else WorkerOutcome.LOST
            }
            mutex.withLock {
                val current = states.getValue(task.id)
                val elapsedSeconds = (clock.monotonicNanos() - taskStartedAt).coerceAtLeast(1) / 1_000_000_000.0
                val size = task.size ?: current.progress?.logicalBytes
                val finalProgress = if (success && !cancelled) {
                    (current.progress ?: TransferProgress(size ?: 0, null, null)).copy(
                        logicalBytes = size ?: current.progress?.logicalBytes ?: 0,
                        matchedBytes = if (task.fileCount > 1 && size != null && current.progress?.matchedBytes != null && current.progress.literalBytes != null)
                            current.progress.matchedBytes + (size - current.progress.matchedBytes - current.progress.literalBytes).coerceAtLeast(0)
                            else current.progress?.matchedBytes,
                        bytesPerSecond = size?.toDouble()?.div(elapsedSeconds), remainingSeconds = null, percent = 100,
                    )
                } else current.progress
                states[task.id] = current.copy(
                    status = if (cancelled) TaskStatus.CANCELLED else if (success) TaskStatus.SUCCEEDED else TaskStatus.FAILED,
                    error = error, warning = warning, progress = finalProgress,
                )
                workers[id] = WorkerState(id, WorkerStatus.IDLE, task.id)
            }
            if (success) completedJobs++
        }
    }

    private suspend fun connectionAlive(transport: TransferSession): Boolean = try {
        transport.isConnected()
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        false
    } catch (_: Exception) { false }

    private fun failureMessage(details: String?, code: Int): String {
        val reason = details?.takeIf { it.isNotBlank() }?.replace(SHELL_PREFIX, "")?.trimEnd('.', ' ')
        return "${reason ?: "rsync failed"} (code $code)."
    }

    private companion object {
        /** rsync exit code 24: some source files vanished while the transfer ran. */
        const val VANISHED_SOURCE_FILES = 24
        const val VANISHED_WARNING = "Some source files vanished before they were copied (code 24)."
        val SHELL_PREFIX = Regex("^(?:/[^ ]*/)?(?:bash|sh|dash|zsh|ksh): (?:line [0-9]+: )?")
    }

    /** Cancel the run and unblock transports. awaitCompletion() waits for worker cleanup/accounting. */
    suspend fun close(): Unit = withContext(NonCancellable) {
        val (owned, final) = mutex.withLock {
            cancelled = true
            queue.clear()
            for ((id, state) in states.toMap()) {
                if (state.status == TaskStatus.QUEUED) states[id] = state.copy(status = TaskStatus.CANCELLED)
            }
            signalLocked()
            activeTransports.values.toList() to if (liveWorkers == 0) finishLocked() else null
        }
        supervisor.cancel()
        owned.forEach { runCatching { it.close() } }
        publishCompletion(final)
    }
}
