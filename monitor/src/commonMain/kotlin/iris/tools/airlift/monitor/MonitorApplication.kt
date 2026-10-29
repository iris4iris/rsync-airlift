package iris.tools.airlift.monitor

import iris.tools.airlift.*
import kotlinx.io.files.Path
import kotlinx.coroutines.*

internal suspend fun launchMonitor(args: List<String>, platform: MonitorApplicationPlatform = monitorApplicationPlatform()) {
    if (args.isEmpty() || "--help" in args) {
        println("""
            Rsync Airlift Monitor
            --demo [--endpoint FILE] [--swing] Start simulated transfers and open TUI
            --attach FILE [--swing]            Attach TUI to an existing dispatcher
            --serve-demo [--endpoint FILE]    Serve simulated transfers without opening TUI
            [options] SOURCE [SOURCE ...] DESTINATION [--files FILE]
                    [--workers N] [--port N] [--identity FILE] [--endpoint FILE] [--swing]
                    [--rsync "-avH --partial --inplace --bwlimit=90000"]
                    [--ssh "-o Compression=no"] [--attempts N]
            --serve [options] SOURCE DESTINATION  Start transfers without opening TUI

            --workers N: 1-${AirliftConfig.MAX_WORKERS} (default 16). --attempts N: rsync attempts per job, 1-10 (default 1).
            --ssh "OPTIONS": extra OpenSSH client options for every connection; port, key,
            multiplexing and forwarding stay managed by Airlift (use --port and --identity).
            File list: UTF-8 relative paths, one per line (optional BOM and leading ./);
            --null reads NUL-separated paths.
            Multiple sources are accepted; the last path is always the destination.
            Quote masks to let Airlift expand them; expanded local file lists also work.
            An existing path is never expanded; escape literal \*, \? and \[ in masks.
            * and ? stay within a name (and match leading dots), ** crosses directories,
            [abc] and [!abc] match one character; a mask ending in / selects whole directories.
            Without --files: discover source files. Mask + --files: union without duplicates.
            With --files and no mask: use only the list. All sizes are collected before copying.
            A mask or source that selects no files is an error.
            Tasks run largest first. Remote discovery requires a POSIX sh and GNU find over SSH.
            --batch-min-size SIZE fills each rsync job to SIZE or more (default 5MiB; MB is decimal).
            Confirm From/To before real transfers; --yes skips confirmation for automation.
            MC /sh://user@host/path endpoints are recognized automatically (--mc is optional).
            --demo is simulated data, not network traffic. No arguments starts no transfers.
            SOURCE DESTINATION starts real transfers and opens the monitor (--run is optional).
            Remote endpoint: user@host:/directory/. Swap the paths to reverse direction.
            Default UI: real terminal. --swing explicitly opens a graphical terminal window.
            Prefer the installed launcher for TUI; SSH requires a PTY (ssh -t).
            P pause new tasks; +/- workers; 1/2/3 views; arrows select; F2 details; Q detach.
            After completion, Q exits an interactive run. --serve stays alive; --attach only detaches.
            Detaching during a transfer keeps the server alive. Ctrl+C stops the server/transfers.
            For SSH disconnect survival, run the server in tmux or as a service.
        """.trimIndent())
        return
    }
    val arguments = LaunchArguments.parse(args)
    val options = arguments.options
    val enabled = arguments.enabled
    if (options.containsKey("--attach")) {
        require(options.size == 1 && enabled.all { it == "--swing" }) { "--attach accepts only --swing" }
        AirliftMonitor(platform.remoteSource(Path(options.getValue("--attach"))), swing = "--swing" in enabled).run()
        return
    }

    val demo = "--demo" in enabled || "--serve-demo" in enabled
    require("--swing" !in enabled || "--demo" in enabled || "--run" in enabled) { "--swing requires --demo, --run or --attach" }
    val allRoots = if (demo) emptyList() else TransferRoots.parseSources(arguments.paths)
    val roots = allRoots.firstOrNull()
    val initialConfig = if (demo) {
        val root = platform.temporaryDirectory("airlift-demo-")
        AirliftConfig("demo@localhost", "/demo", root.resolve("files"), workers = arguments.workers ?: 4,
            maxAttempts = arguments.attempts ?: 1, logDirectory = root.resolve("logs"))
    } else requireNotNull(roots).let { roots -> AirliftConfig(
        // Preparation replaces the upload source root with the discovered source directory.
        host = roots.host, remoteRoot = roots.remoteRoot, localRoot = Path(roots.localRoot),
        workers = arguments.workers ?: 16, port = arguments.port ?: 22,
        identityFile = options["--identity"]?.let { kotlinx.io.files.Path(it) },
        direction = roots.direction, maxAttempts = arguments.attempts ?: 1,
        rsyncOptions = options["--rsync"], sshOptions = options["--ssh"],
    ) }
    if (!demo && !confirmTransfer(arguments.paths, "--yes" in enabled, "--swing" in enabled)) {
        println("Synchronization cancelled. No transfers started.")
        return
    }
    val plan = if (demo) PreparedTransfer(initialConfig, (1..48).map { TransferTask("demo/folder-${it % 4}/file-$it.bin", (20 + it % 7) * 1024L * 1024) })
        else {
            checkSshConnection(initialConfig)
            println("Collecting source files and sizes...")
            prepareSources(initialConfig, allRoots, options["--files"]?.let(::Path), "--null" in enabled).also {
                it.warnings.forEach { warning -> println("Warning: $warning") }
                println("Prepared ${it.tasks.size} files, ${it.tasks.sumOf { task -> requireNotNull(task.size) }} bytes; largest first.")
            }
        }
    val config = plan.config
    val tasks = if (demo) plan.tasks else {
        val collapser = DirectoryCollapser(plan.inventories, options["--rsync"])
        batchSmallFiles(plan.tasks, batchSize(options["--batch-min-size"] ?: "5MiB")).map(collapser::optimize)
    }
    if (!demo) println("${plan.tasks.size} files scheduled in ${tasks.size} rsync jobs; " +
        "${tasks.sumOf { it.entries.ifEmpty { it.files.ifEmpty { listOf(it.path) } }.size }} file-list entries after directory optimization.")
    val endpoint = monitorEndpoint(options["--endpoint"], if (demo) config.localRoot.parent else null)
    val dispatcher = if (demo) AirliftDispatcher(config, tasks, demoSessions()) else AirliftDispatcher(config, tasks)
    val source = DispatcherMonitorSource(dispatcher, if (demo) "RSYNC AIRLIFT | DEMO - SIMULATED DATA" else "RSYNC AIRLIFT",
        if (arguments.paths.size > 2) arguments.paths.dropLast(1).joinToString("; ") else dispatcher.sourceDescription)
    runMonitorService(dispatcher, source, endpoint, demo,
        "--demo" in enabled || "--run" in enabled, "--swing" in enabled, platform)
}

internal suspend fun runMonitorService(
    dispatcher: AirliftDispatcher, source: MonitorSource, endpoint: Path,
    demo: Boolean, interactive: Boolean, swing: Boolean,
    platform: MonitorApplicationPlatform,
    runPanel: suspend () -> Unit = { AirliftMonitor(source, swing = swing).run() },
) {
    var server: MonitorService? = null
    var shutdown: ShutdownRegistration? = null
    val stop = CompletableDeferred<Unit>()
    try {
        server = platform.serve(source, endpoint)
        shutdown = platform.onShutdown {
            // Each step runs even if an earlier one fails, so the endpoint is removed and the service ends.
            try { dispatcher.close() }
            finally { try { server.close() } finally { stop.complete(Unit) } }
        }
        dispatcher.start()
        println("${if (demo) "DEMO (simulated data). " else ""}Monitor connection: $endpoint")
        println("Attach: rsync-airlift-monitor --attach \"$endpoint\"")
        println("Ctrl+C stops dispatcher. Q detaches while running; after completion Q exits an interactive run.")
        if (interactive) {
            // The panel is only a view: a terminal failure must not cancel the transfers it shows.
            val panelFailure = try { runPanel(); null }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { error }
            if (panelFailure != null) println("Monitor panel stopped: ${panelFailure.message ?: panelFailure::class.simpleName}")
            else if (dispatcher.snapshot().finishedAtMillis != null) return
            println("Monitor detached. Dispatcher remains available at $endpoint")
        }
        stop.await()
    } finally {
        withContext(NonCancellable) {
            try { shutdown?.remove() }
            finally { try { dispatcher.close() } finally { server?.close() } }
        }
    }
}
