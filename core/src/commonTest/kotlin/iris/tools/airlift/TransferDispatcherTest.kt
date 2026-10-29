package iris.tools.airlift

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.io.files.Path
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AirliftDispatcherTest {
    private fun settings(workers: Int = 2, maxAttempts: Int = 1, retryDelayMillis: Long = 1000) =
        AirliftConfig("user@host", "/source", Path("destination"), workers = workers,
            maxAttempts = maxAttempts, retryDelayMillis = retryDelayMillis)

    private class Logs : DispatcherLogStore {
        val attempts = mutableListOf<String>()
        override suspend fun createRunDirectory() = Path("logs/run-test")
        override fun attemptPath(run: Path, worker: Int, taskIndex: Int, attempt: Int): Path {
            val name = "worker-$worker-task-$taskIndex-attempt-$attempt.log"
            attempts.add(name)
            return Path("$run/$name")
        }
    }

    private fun TestScope.engine(
        tasks: List<TransferTask>, settings: AirliftConfig = settings(workers = 2),
        logs: DispatcherLogStore = Logs(), factory: TransferSessionFactory,
    ) = AirliftDispatcher(settings, tasks, factory, logs, object : DispatcherClock {
        override fun wallTimeMillis() = 10_000L + testScheduler.currentTime
        override fun monotonicNanos() = testScheduler.currentTime * 1_000_000L
    }, StandardTestDispatcher(testScheduler), object : DispatcherPaths {
        override fun absolute(path: Path) = Path("/prepared", path.toString())
    })

    private fun factory(action: suspend (TransferTask, suspend (TransferProgress) -> Unit) -> Int) = TransferSessionFactory {
        object : TransferSession {
            override suspend fun connect() {}
            override suspend fun transfer(task: TransferTask, logFile: Path, progress: suspend (TransferProgress) -> Unit) = action(task, progress)
            override suspend fun close() {}
        }
    }

    @Test fun `original transfer error reaches failed batch snapshot before exit code`() = runTest {
        val dispatcher = engine(listOf(TransferTask("a", 20, files = listOf("a", "b"))),
            settings(workers = 1), factory = TransferSessionFactory {
                object : TransferSession {
                    override val failureDetails = "bash: rsync: command not found"
                    override suspend fun connect() {}
                    override suspend fun transfer(task: TransferTask, logFile: Path, progress: suspend (TransferProgress) -> Unit) = 127
                    override suspend fun close() {}
                }
            })
        try {
            dispatcher.start()
            val result = dispatcher.awaitCompletion().tasks.single()
            assertEquals(TaskStatus.FAILED, result.status)
            assertEquals("rsync: command not found (code 127).", result.error)
        } finally { dispatcher.close() }
    }

    @Test fun `connections start in waves of five and wait for the slowest attempt`() = runTest {
        val gates = (1..16).associateWith { CompletableDeferred<Unit>() }
        val started = mutableListOf<Int>()
        val dispatcher = engine((1..17).map { TransferTask("file-$it") }, settings(workers = 16),
            factory = TransferSessionFactory { id ->
                object : TransferSession {
                    override suspend fun connect() { started.add(id); gates.getValue(id).await() }
                    override suspend fun transfer(task: TransferTask, logFile: Path, progress: suspend (TransferProgress) -> Unit) = 0
                    override suspend fun close() {}
                }
            })
        try {
            dispatcher.pause()
            dispatcher.start()
            runCurrent()
            assertEquals((1..5).toList(), started)
            (1..4).forEach { gates.getValue(it).complete(Unit) }
            runCurrent()
            assertEquals(5, started.size)
            gates.getValue(5).complete(Unit)
            runCurrent()
            assertEquals((1..10).toList(), started)
            (6..10).forEach { gates.getValue(it).complete(Unit) }
            runCurrent()
            assertEquals((1..15).toList(), started)
            (11..15).forEach { gates.getValue(it).complete(Unit) }
            runCurrent()
            assertEquals((1..16).toList(), started)
        } finally { dispatcher.close() }
        assertTrue(dispatcher.awaitCompletion().cancelled)
    }

    @Test fun `new workers wait for current wave including failed connection attempts`() = runTest {
        val gates = (1..8).associateWith { CompletableDeferred<Unit>() }
        val started = mutableListOf<Int>()
        val dispatcher = engine((1..10).map { TransferTask("file-$it") }, settings(workers = 5),
            factory = TransferSessionFactory { id ->
                object : TransferSession {
                    override suspend fun connect() { started.add(id); gates.getValue(id).await() }
                    override suspend fun transfer(task: TransferTask, logFile: Path, progress: suspend (TransferProgress) -> Unit) = 0
                    override suspend fun close() {}
                }
            })
        try {
            dispatcher.pause()
            dispatcher.start()
            runCurrent()
            dispatcher.setWorkerCount(8)
            gates.getValue(3).completeExceptionally(IllegalStateException("Connection refused"))
            listOf(1, 2, 4).forEach { gates.getValue(it).complete(Unit) }
            runCurrent()
            assertEquals((1..5).toList(), started)
            assertEquals(WorkerStatus.FAILED, dispatcher.snapshot().workers.single { it.id == 3 }.status)
            gates.getValue(5).complete(Unit)
            runCurrent()
            assertEquals((1..8).toList(), started)
        } finally { dispatcher.close() }
        assertTrue(dispatcher.awaitCompletion().cancelled)
    }

    @Test fun `source and destination descriptions belong to the shared dispatcher`() = runTest {
        for (direction in TransferDirection.entries) {
            val config = settings().copy(direction = direction)
            val dispatcher = engine(emptyList(), config, factory = factory { _, _ -> error("Unexpected transfer") })
            try {
                val local = Path("/prepared", "destination").toString()
                assertEquals(if (direction == TransferDirection.DOWNLOAD) "user@host:/source" else local, dispatcher.sourceDescription)
                assertEquals(if (direction == TransferDirection.DOWNLOAD) local else "user@host:/source", dispatcher.destinationDescription)
                dispatcher.start()
                assertTrue(dispatcher.awaitCompletion().tasks.isEmpty())
            } finally { dispatcher.close() }
        }
    }

    @Test fun `common dispatcher pauses grows and consumes every job exactly once`() = runTest {
        val seen = mutableListOf<String>()
        val dispatcher = engine((1..20).map { TransferTask("$it", 100) }, factory = factory { task, report ->
            seen.add(task.id); delay(100); report(TransferProgress(100, 1.0, 1)); 0
        })
        try {
            dispatcher.pause()
            dispatcher.start()
            runCurrent()
            assertTrue(seen.isEmpty())
            dispatcher.setWorkerCount(4)
            dispatcher.resume()
            val result = dispatcher.awaitCompletion()
            assertEquals(20, result.completed)
            assertEquals(20, seen.toSet().size)
            assertEquals(20, seen.size)
            assertTrue(result.workers.all { it.status == WorkerStatus.FINISHED })
            assertTrue(result.tasks.all { it.progress!!.bytesPerSecond == 1000.0 })
        } finally { dispatcher.close() }
    }

    @Test fun `retry uses virtual time attempt logs and full batch accounting`() = runTest {
        val logs = Logs()
        var attempts = 0
        val dispatcher = engine(listOf(TransferTask("a", 100, files = listOf("a", "b"))),
            settings(1, 2, 1000), logs, factory { _, report ->
                delay(100)
                report(TransferProgress(10, 100.0, null, matchedBytes = 50, literalBytes = 10))
                if (++attempts == 1) 23 else 0
            })
        try {
            dispatcher.start()
            val result = dispatcher.awaitCompletion()
            assertEquals(2, result.completed)
            assertEquals(1200, result.finishedAtMillis!! - result.startedAtMillis!!)
            assertEquals(2, result.tasks.single().attempts)
            assertEquals(listOf("worker-1-task-0-attempt-1.log", "worker-1-task-0-attempt-2.log"), logs.attempts)
            val final = result.tasks.single().progress!!
            assertEquals(50, final.matchedBytes)
            assertEquals(10, final.literalBytes)
            assertEquals(100.0 / 1.2, final.bytesPerSecond)
            assertNull(final.remainingSeconds)
        } finally { dispatcher.close() }
    }

    @Test fun `immediate close finalizes coroutines without creating unused sessions`() = runTest {
        var created = 0
        var connected = 0
        var closed = 0
        val transports = TransferSessionFactory {
            created++
            object : TransferSession {
                override suspend fun connect() { connected++ }
                override suspend fun transfer(task: TransferTask, logFile: Path, progress: suspend (TransferProgress) -> Unit): Int = error("Unexpected transfer")
                override suspend fun close() { closed++ }
            }
        }
        val dispatcher = engine((1..20).map { TransferTask("$it") }, settings(8), factory = transports)
        dispatcher.start()
        dispatcher.close()
        val final = dispatcher.awaitCompletion()
        assertEquals(0, connected)
        assertEquals(0, created)
        assertEquals(created, closed)
        assertTrue(final.cancelled)
        assertTrue(final.tasks.all { it.status == TaskStatus.CANCELLED })
        assertTrue(final.workers.all { it.status == WorkerStatus.STOPPED })
    }

    @Test fun `closing transport unblocks a transfer that does not cooperate with coroutine cancellation`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        var closed = false
        val dispatcher = engine(listOf(TransferTask("a"), TransferTask("b")), settings(1), factory = TransferSessionFactory {
            object : TransferSession {
                override suspend fun connect() {}
                override suspend fun transfer(task: TransferTask, logFile: Path, progress: suspend (TransferProgress) -> Unit): Int {
                    entered.complete(Unit)
                    withContext(NonCancellable) { released.await() }
                    return 0
                }
                override suspend fun close() { closed = true; released.complete(Unit) }
            }
        })
        dispatcher.start()
        entered.await()
        dispatcher.close()
        val final = dispatcher.awaitCompletion()
        assertTrue(closed)
        assertTrue(final.tasks.all { it.status == TaskStatus.CANCELLED })
    }

    @Test fun `close during retry delay prevents further attempts`() = runTest {
        var attempts = 0
        val dispatcher = engine(listOf(TransferTask("a")), settings(1, 3, 60_000), factory = factory { _, _ -> attempts++; 23 })
        dispatcher.start()
        runCurrent()
        assertEquals(1, attempts)
        dispatcher.close()
        assertTrue(dispatcher.awaitCompletion().cancelled)
        assertEquals(1, attempts)
        assertEquals(0, testScheduler.currentTime)
    }

    @Test fun `retirement is reserved while resource cleanup is suspended`() = runTest {
        val gates = mapOf(1 to CompletableDeferred<Unit>(), 2 to CompletableDeferred<Unit>())
        val cleanup = CompletableDeferred<Unit>()
        val seen = mutableMapOf<Int, MutableList<String>>()
        val dispatcher = engine(listOf(TransferTask("a"), TransferTask("b"), TransferTask("c")), factory = TransferSessionFactory { id ->
            object : TransferSession {
                override suspend fun connect() {}
                override suspend fun transfer(task: TransferTask, logFile: Path, progress: suspend (TransferProgress) -> Unit): Int {
                    seen.getOrPut(id) { mutableListOf() }.add(task.id)
                    gates.getValue(id).await()
                    return 0
                }
                override suspend fun close() { if (id == 2) cleanup.await() }
            }
        })
        try {
            dispatcher.start()
            runCurrent()
            dispatcher.setWorkerCount(1)
            gates.getValue(2).complete(Unit)
            runCurrent() // Worker 2 retires but its transport close is still suspended.
            gates.getValue(1).complete(Unit)
            runCurrent()
            assertEquals(listOf("a", "c"), seen.getValue(1).toList())
            assertEquals(listOf("b"), seen.getValue(2).toList())
            assertEquals(2, dispatcher.snapshot().workers.size)
            cleanup.complete(Unit)
            val final = dispatcher.awaitCompletion()
            assertEquals(3, final.completed)
            assertEquals(WorkerStatus.STOPPED, final.workers.single { it.id == 2 }.status)
        } finally { gates.values.forEach { it.complete(Unit) }; cleanup.complete(Unit); dispatcher.close() }
    }

    @Test fun `connection failures finish without respawn loops`() = runTest {
        var created = 0
        val dispatcher = engine(listOf(TransferTask("a")), settings(1), factory = TransferSessionFactory {
            created++
            object : TransferSession {
                override suspend fun connect() { error("Cannot connect") }
                override suspend fun transfer(task: TransferTask, logFile: Path, progress: suspend (TransferProgress) -> Unit) = 0
                override suspend fun close() {}
            }
        })
        try {
            dispatcher.start()
            val final = dispatcher.awaitCompletion()
            assertEquals(1, created)
            assertEquals(1, final.failed)
            assertEquals("Cannot connect", final.workers.single().error)
        } finally { dispatcher.close() }
    }

    @Test fun `cancelling one waiter does not cancel dispatcher completion`() = runTest {
        val dispatcher = engine(listOf(TransferTask("a")), factory = factory { _, _ -> delay(100); 0 })
        try {
            dispatcher.start()
            val waiter = launch { dispatcher.awaitCompletion() }
            runCurrent()
            waiter.cancelAndJoin()
            assertEquals(1, dispatcher.awaitCompletion().completed)
        } finally { dispatcher.close() }
    }

    @Test fun `transport-local connection timeout fails once without respawn`() = runTest {
        var created = 0
        val dispatcher = engine(listOf(TransferTask("a")), settings(1), factory = TransferSessionFactory {
            created++
            object : TransferSession {
                override suspend fun connect() { withTimeout(10) { delay(20) } }
                override suspend fun transfer(task: TransferTask, logFile: Path, progress: suspend (TransferProgress) -> Unit) = 0
                override suspend fun close() {}
            }
        })
        try {
            dispatcher.start()
            assertEquals(1, dispatcher.awaitCompletion().failed)
            assertEquals(1, created)
            assertEquals(WorkerStatus.FAILED, dispatcher.snapshot().workers.single().status)
        } finally { dispatcher.close() }
    }

    @Test fun `transport-local transfer timeout follows the retry policy`() = runTest {
        var attempts = 0
        val dispatcher = engine(listOf(TransferTask("a")), settings(1, 2, 100), factory = factory { _, _ ->
            if (++attempts == 1) withTimeout(10) { delay(20) }
            0
        })
        try {
            dispatcher.start()
            assertEquals(1, dispatcher.awaitCompletion().completed)
            assertEquals(2, attempts)
        } finally { dispatcher.close() }
    }

    @Test fun `startup failure finalizes the run and prevents reuse`() = runTest {
        val logs = object : DispatcherLogStore {
            override suspend fun createRunDirectory(): Path = error("No space")
            override fun attemptPath(run: Path, worker: Int, taskIndex: Int, attempt: Int) = error("Unexpected log")
        }
        val dispatcher = engine(listOf(TransferTask("a")), logs = logs, factory = factory { _, _ -> error("Unexpected transfer") })
        assertFailsWith<IllegalStateException> { dispatcher.start() }
        assertTrue(dispatcher.awaitCompletion().cancelled)
        assertFailsWith<IllegalStateException> { dispatcher.start() }
    }

    @Test fun `slow log creation does not hold the state mutex or prevent closing`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val logs = object : DispatcherLogStore {
            override suspend fun createRunDirectory(): Path {
                entered.complete(Unit)
                release.await()
                return Path("logs/run")
            }
            override fun attemptPath(run: Path, worker: Int, taskIndex: Int, attempt: Int) = error("Unexpected log")
        }
        val dispatcher = engine(listOf(TransferTask("a")), logs = logs, factory = factory { _, _ -> error("Unexpected transfer") })
        val startup = launch { assertFailsWith<IllegalStateException> { dispatcher.start() } }
        entered.await()
        assertEquals(1, dispatcher.snapshot().queued)
        dispatcher.close()
        assertTrue(dispatcher.awaitCompletion().cancelled)
        release.complete(Unit)
        startup.join()
    }
}
