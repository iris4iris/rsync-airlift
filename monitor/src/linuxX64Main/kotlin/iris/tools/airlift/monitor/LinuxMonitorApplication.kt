@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package iris.tools.airlift.monitor

import iris.tools.airlift.SshAuthentication
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.io.files.Path
import platform.posix.*
import iris.tools.airlift.monitor.posix.*

fun main(args: Array<String>) = runBlocking {
    // A closed stdout/stderr pipe (| head, a dead log reader) must surface as EPIPE, not kill the dispatcher
    // and orphan its SSH masters. Spawned children get the default disposition back before exec.
    airlift_ignore_sigpipe()
    var status = 0
    try {
        if (args.isNotEmpty() && "--help" !in args) {
            val parsed = LaunchArguments.parse(args.toList())
            require("--swing" !in parsed.enabled) { "Swing is only available in the JVM version" }
            if (parsed.enabled.any { it == "--run" || it == "--demo" } || "--attach" in parsed.options) {
                check(hasInteractiveConsole()) { "Native TUI requires a terminal (SSH: ssh -t). Use --serve for headless transfers." }
            }
        }
        launchMonitor(args.toList())
    } catch (error: Exception) { fputs("Airlift: ${error.message}\n", stderr); status = 1 }
    finally { SshAuthentication.clearPassword() }
    if (status != 0) exit(status)
}
internal actual fun monitorApplicationPlatform(): MonitorApplicationPlatform = object : MonitorApplicationPlatform {
    override fun remoteSource(endpoint: Path): MonitorSource = RemoteMonitorSource(endpoint)
    override fun serve(source: MonitorSource, endpoint: Path): MonitorService {
        val server = MonitorServer(source, endpoint)
        return object : MonitorService { override fun close() = server.close() }
    }
    /** SIGINT, SIGTERM and SIGHUP (unless ignored at launch, e.g. by nohup) run [action] once, like JVM shutdown hooks. */
    override fun onShutdown(action: suspend () -> Unit): ShutdownRegistration {
        val signals = airlift_install_signals() ?: linuxError("install signal handlers")
        // An uncaught exception would abort a Native process; report it instead, as a JVM hook thread does.
        val reporter = CoroutineExceptionHandler { _, error -> fputs("Airlift: shutdown failed: ${error.message}\n", stderr) }
        val scope = CoroutineScope(SupervisorJob() + preparationDispatcher() + reporter)
        val job = scope.launch {
            while (airlift_stopping() == 0) delay(50)
            withContext(NonCancellable) { action() }
        }
        return object : ShutdownRegistration {
            private var removed = false
            override fun remove() {
                if (!removed) { removed = true; airlift_restore_signals(signals); job.cancel(); scope.cancel() }
            }
        }
    }
    override suspend fun temporaryDirectory(prefix: String): Path = withContext(preparationDispatcher()) {
        require('/' !in prefix && '\u0000' !in prefix)
        val parent = getenv("TMPDIR")?.toKString()?.takeIf { it.isNotEmpty() } ?: "/tmp"
        val bytes = "$parent/${prefix}XXXXXX".encodeToByteArray() + byteArrayOf(0)
        bytes.usePinned { Path((mkdtemp(it.addressOf(0)) ?: linuxError("mkdtemp")).toKString()) }
    }
}
