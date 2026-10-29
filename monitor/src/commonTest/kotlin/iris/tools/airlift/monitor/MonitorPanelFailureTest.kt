package iris.tools.airlift.monitor

import iris.tools.airlift.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.io.files.Path
import kotlin.test.*

/** A broken panel or terminal must not cancel transfers; shutdown must finish even when a step fails. */
@OptIn(ExperimentalCoroutinesApi::class)
class MonitorPanelFailureTest {
    private class Platform(private val failFirstClose: Boolean = false) : MonitorApplicationPlatform {
        var shutdown: (suspend () -> Unit)? = null
        var closeCalls = 0
        override fun remoteSource(endpoint: Path): MonitorSource = error("not attaching")
        override suspend fun temporaryDirectory(prefix: String): Path = error("not creating demo")
        override fun serve(source: MonitorSource, endpoint: Path): MonitorService = object : MonitorService {
            override fun close() { closeCalls++; if (failFirstClose && closeCalls == 1) error("endpoint removal failed") }
        }
        override fun onShutdown(action: suspend () -> Unit): ShutdownRegistration {
            shutdown = action
            return object : ShutdownRegistration { override fun remove() {} }
        }
    }

    private fun TestScope.dispatcher(tasks: List<TransferTask> = emptyList()) = AirliftDispatcher(
        AirliftConfig("user@host", "/source", Path("destination"), workers = 1), tasks,
        transports = TransferSessionFactory {
            object : TransferSession {
                override suspend fun connect() {}
                override suspend fun transfer(task: TransferTask, logFile: Path, progress: suspend (TransferProgress) -> Unit): Int {
                    delay(10_000)
                    return 0
                }
                override suspend fun close() {}
            }
        },
        logs = object : DispatcherLogStore {
            override suspend fun createRunDirectory() = Path("logs")
            override fun attemptPath(run: Path, worker: Int, taskIndex: Int, attempt: Int) = Path(run, "attempt")
        }, dispatcher = StandardTestDispatcher(testScheduler),
    )

    @Test fun `failing panel leaves transfers running as a detached service`() = runTest {
        val dispatcher = dispatcher(listOf(TransferTask("a", 1)))
        val platform = Platform()
        val job = launch {
            runMonitorService(dispatcher, DispatcherMonitorSource(dispatcher), Path("endpoint"),
                false, true, false, platform, runPanel = { error("Terminal write failed") })
        }
        runCurrent()
        assertTrue(job.isActive)
        assertEquals(0, platform.closeCalls)
        assertNull(dispatcher.snapshot().finishedAtMillis)
        advanceUntilIdle()
        val finished = dispatcher.snapshot()
        assertNotNull(finished.finishedAtMillis)
        assertEquals(listOf(TaskStatus.SUCCEEDED), finished.tasks.map { it.status })
        assertTrue(job.isActive)
        platform.shutdown!!()
        job.join()
        assertTrue(platform.closeCalls > 0)
    }

    @Test fun `shutdown completes even when closing the server fails`() = runTest {
        val dispatcher = dispatcher()
        val platform = Platform(failFirstClose = true)
        val job = launch {
            runMonitorService(dispatcher, DispatcherMonitorSource(dispatcher), Path("endpoint"), false, false, false, platform)
        }
        runCurrent()
        assertFailsWith<IllegalStateException> { platform.shutdown!!() }
        job.join()
        assertTrue(job.isCompleted && !job.isCancelled)
        assertEquals(2, platform.closeCalls)
    }
}
