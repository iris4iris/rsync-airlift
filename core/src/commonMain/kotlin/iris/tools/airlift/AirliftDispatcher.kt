package iris.tools.airlift

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        require(count > 0)
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
            val count = minOf(5, targetWorkers - (liveWorkers - retiring.size))
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

    private suspend fun runWorker(id: Int, wave: ConnectionWave) {
        var transport: TransferSession? = null
        var fatal: String? = null
        try {
            try {
                wave.previous.await()
                currentCoroutineContext().ensureActive()
                transport = transports.create(id)
                mutex.withLock {
                    if (cancelled) return
                    activeTransports[id] = transport
                }
                transport.connect()
            } finally {
                // Failures/cancellation must not strand later waves. Notify outside
                // the state mutex, since completion can resume another worker.
                withContext(NonCancellable) {
                    val release = mutex.withLock { --wave.remaining == 0 }
                    if (release) wave.completed.complete(Unit)
                }
            }
            while (true) {
                val task = takeTask(id) ?: break
                val taskStartedAt = clock.monotonicNanos()
                var error: String? = null
                var success = false
                for (attempt in 1..config.maxAttempts) {
                    if (mutex.withLock { cancelled }) break
                    val log = logs.attemptPath(runDirectory, id, taskIndexes.getValue(task.id), attempt)
                    mutex.withLock {
                        states[task.id] = states.getValue(task.id).copy(attempts = attempt, progress = null,
                            logFile = log, progressUpdatedAtNanos = null)
                    }
                    try {
                        val code = transport.transfer(task, log) { progress ->
                            mutex.withLock {
                                states[task.id] = states.getValue(task.id).copy(progress = progress,
                                    progressUpdatedAtNanos = clock.monotonicNanos())
                            }
                        }
                        success = code == 0
                        val details = if (success) null else transport.failureDetails?.takeIf { it.isNotBlank() }
                        error = if (success) null else {
                            val reason = details?.replace(
                                Regex("^(?:/[^ ]*/)?(?:bash|sh|dash|zsh|ksh): (?:line [0-9]+: )?"), ""
                            )?.trimEnd('.', ' ')
                            "${reason ?: "rsync failed"} (code $code)."
                        }
                    } catch (e: CancellationException) {
                        // A transport-local timeout is an attempt failure, not cancellation of the run.
                        currentCoroutineContext().ensureActive()
                        error = e.message ?: "Transfer cancelled"
                    } catch (e: Exception) {
                        error = e.message ?: "Transfer failed"
                    }
                    if (success) break
                    if (attempt < config.maxAttempts) delay(config.retryDelayMillis)
                }
                mutex.withLock {
                    val current = states.getValue(task.id)
                    val elapsedSeconds = (clock.monotonicNanos() - taskStartedAt).coerceAtLeast(1) / 1_000_000_000.0
                    val size = task.size ?: current.progress?.logicalBytes
                    val finalProgress = if (success && !cancelled) {
                        (current.progress ?: TransferProgress(size ?: 0, null, null)).copy(
                            logicalBytes = size ?: current.progress?.logicalBytes ?: 0,
                            bytesPerSecond = size?.toDouble()?.div(elapsedSeconds), remainingSeconds = null, percent = 100,
                        )
                    } else current.progress
                    states[task.id] = current.copy(
                        status = if (cancelled) TaskStatus.CANCELLED else if (success) TaskStatus.SUCCEEDED else TaskStatus.FAILED,
                        error = error, progress = finalProgress,
                    )
                    workers[id] = WorkerState(id, WorkerStatus.IDLE, task.id)
                }
            }
        } catch (e: CancellationException) {
            val stopping = withContext(NonCancellable) { mutex.withLock { cancelled } }
            if (stopping) throw e
            fatal = e.message ?: "Worker operation cancelled"
        } catch (e: Exception) {
            fatal = e.message ?: "Worker failed"
        } finally {
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
