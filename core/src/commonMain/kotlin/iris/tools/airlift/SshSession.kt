package iris.tools.airlift

import iris.tools.airlift.process.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.files.Path

class SshSessionFactory(
    private val config: AirliftConfig,
    private val launcher: ProcessLauncher = platformProcessLauncher(),
    private val files: SshFiles = DefaultSshFiles(),
    private val authentication: ProcessAuthentication = ProcessAuthentication { SshAuthentication.configure(it) },
    private val paths: DispatcherPaths = platformPaths(),
    private val clock: DispatcherClock = platformClock(),
) : TransferSessionFactory {
    override suspend fun create(workerId: Int): TransferSession {
        val directory = withContext(NonCancellable) { files.temporaryDirectory("airlift-") }
        val session = SshSession(config, launcher, files, authentication, paths, clock, directory)
        try { currentCoroutineContext().ensureActive(); return session }
        catch (e: CancellationException) { session.close(); throw e }
    }
}

private class SshSession(
    private val config: AirliftConfig, private val launcher: ProcessLauncher, private val files: SshFiles,
    private val authentication: ProcessAuthentication, private val paths: DispatcherPaths,
    private val clock: DispatcherClock, private val directory: Path,
) : TransferSession {
    override var failureDetails: String? = null
        private set
    private val mutex = Mutex()
    private val cleanup = Mutex()
    private var closed = false
    private var cleaned = false
    private val processes = mutableSetOf<ChildProcess>()
    private var master: ChildProcess? = null
    private val socket = Path(directory, "s")
    private val userOptions = config.sshArguments
    // OpenSSH keeps the first value of an option, so Airlift's options precede the user's --ssh options.
    private val base get() = buildList {
        add(config.sshExecutable)
        addAll(listOf("-p", config.port.toString(), "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
            "-o", "ConnectTimeout=${config.connectTimeoutSeconds}", "-o", "ServerAliveInterval=30", "-o", "ServerAliveCountMax=3"))
        config.identityFile?.let { addAll(listOf("-i", paths.absolute(it).toString())) }
    }
    private fun control(vararg arguments: String) = base + listOf("-S", socket.toString()) + userOptions + arguments

    private suspend fun launch(spec: ProcessSpec): ChildProcess {
        // A started process must be registered or closed even if startup races with cancellation.
        return withContext(NonCancellable) {
            mutex.withLock {
                check(!closed) { "SSH session is closed" }
                launcher.start(spec).also { processes.add(it) }
            }
        }
    }
    private fun spec(command: List<String>, output: Path? = null) = ProcessSpec(command,
        environment = mapOf("LC_ALL" to "C"), mergeError = true,
        output = output?.let { ProcessRedirect.File(it) } ?: ProcessRedirect.Pipe)
    private suspend fun forget(process: ChildProcess) { mutex.withLock { processes.remove(process) } }

    override suspend fun connect() {
        try {
            val master = launch(authentication.prepare(spec(control("-M", "-N", "-o", "ControlPersist=no", config.host), Path(directory, "master.log"))))
            this.master = master
            master.stdin?.close()
            val deadline = clock.monotonicNanos() + (config.connectTimeoutSeconds.toLong() + 2) * 1_000_000_000
            while (clock.monotonicNanos() < deadline) {
                check(master.exitCode() == null) { "SSH master exited: ${files.readTail(Path(directory, "master.log"), 4096).trim()}" }
                if (probeMaster(1000)) return
                delay(100)
            }
            error("SSH ControlMaster connection timed out")
        } catch (e: Throwable) { close(); throw e }
    }

    /** Asks the ControlMaster whether it is still running; never starts a new connection. */
    private suspend fun probeMaster(timeoutMillis: Long): Boolean {
        val probe = launch(spec(control("-O", "check", config.host), Path(directory, "probe.log")))
        val code = try { probe.stdin?.close(); probe.awaitExit(timeoutMillis) }
            finally { withContext(NonCancellable) { probe.close(); forget(probe) } }
        return code == 0
    }

    override suspend fun isConnected(): Boolean {
        val master = master ?: return false
        return master.exitCode() == null && probeMaster(5000)
    }

    override suspend fun transfer(task: TransferTask, logFile: Path, progress: suspend (TransferProgress) -> Unit): Int {
        failureDetails = null
        // Only a download writes locally; for an upload the local root is a source and may even be a file.
        if (config.direction == TransferDirection.DOWNLOAD) files.createDirectories(config.localRoot)
        // ProxyCommand=false: if the master is gone, fail fast instead of opening an unauthenticated connection.
        val transport = (base + listOf("-S", socket.toString(), "-o", "ControlMaster=no", "-o", "ProxyCommand=false") + userOptions)
            .joinToString(" ") { quote(it) }
        val address = config.host.substringAfterLast('@')
        val rsyncHost = if (':' in address) config.host.removeSuffix(address) + "[$address]" else config.host
        val remoteRoot = if (config.direction == TransferDirection.DOWNLOAD) task.sourceRoot ?: config.remoteRoot else config.remoteRoot
        val localRoot = if (config.direction == TransferDirection.UPLOAD) task.sourceRoot?.let { Path(it) } ?: config.localRoot else config.localRoot
        val remote = "$rsyncHost:${remoteRoot.trimEnd('/')}/"
        val local = paths.absolute(localRoot).toString().trimEnd('/', '\\') + "/"
        val source = if (config.direction == TransferDirection.DOWNLOAD) remote else local
        val destination = if (config.direction == TransferDirection.DOWNLOAD) local else remote
        val entries = task.entries
        val command = RsyncCommand.build(config, transport, source, destination, recursive = entries.any { it == "." || it.endsWith('/') })
        val log = files.log(logFile)
        try {
            val process = launch(spec(command))
            try { return coroutineScope {
                val writer = async {
                    val input = requireNotNull(process.stdin)
                    try {
                        val pending = Buffer()
                        (entries.ifEmpty { task.files.ifEmpty { listOf(task.path) } }).forEach {
                            pending.write((it + '\u0000').encodeToByteArray())
                            if (pending.size >= 65536) input.write(pending.readByteArray())
                        }
                        if (pending.size > 0) input.write(pending.readByteArray())
                        input.flush()
                        null
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { e }
                    finally { withContext(NonCancellable) { runCatching { input.close() } } }
                }
                try {
                    val output = requireNotNull(process.stdout)
                    val parser = RsyncOutputParser(task.files.ifEmpty { listOf(task.path) })
                    val record = Buffer()
                    val diagnostics = RsyncDiagnostics()
                    suspend fun consumeRecord() {
                        val text = record.readByteArray().decodeToString()
                        diagnostics.accept(text)
                        parser.parse(text)?.let { progress(it) }
                    }
                    val bytes = ByteArray(8192)
                    while (true) {
                        val count = output.read(bytes)
                        if (count < 0) break
                        log.write(bytes.copyOf(count))
                        for (index in 0 until count) {
                            val byte = bytes[index]
                            if (byte == 13.toByte() || byte == 10.toByte()) {
                                consumeRecord()
                            } else if (record.size < 65536) record.writeByte(byte)
                        }
                        log.flush()
                    }
                    consumeRecord()
                    val code = process.awaitExit()
                    if (code != 0) failureDetails = diagnostics.summary()
                    val inputFailure = writer.await()
                    if (code == 0 && inputFailure != null) throw inputFailure
                    code
                } finally {
                    withContext(NonCancellable) { process.close(); writer.cancelAndJoin(); forget(process) }
                }
            } } finally { withContext(NonCancellable) { process.close(); forget(process) } }
        } finally { withContext(NonCancellable) { log.close() } }
    }

    override suspend fun close(): Unit = withContext(NonCancellable) {
        cleanup.withLock {
            if (cleaned) return@withLock
            val owned = mutex.withLock { closed = true; processes.toList() }
            owned.forEach { runCatching { it.terminate() } }
            owned.forEach { runCatching { it.close() } }
            files.deleteOwnedDirectory(directory)
            cleaned = true
        }
    }
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
