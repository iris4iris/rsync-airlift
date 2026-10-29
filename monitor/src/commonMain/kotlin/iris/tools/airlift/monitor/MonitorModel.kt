package iris.tools.airlift.monitor

import iris.tools.airlift.*

enum class MonitorView { WORKERS, QUEUE, ERRORS }

data class RowSegment(val text: String, val color: MonitorColor = MonitorColor.ANSI.WHITE)
data class MonitorRow(val key: String, val text: String, val task: TaskState? = null, val error: String? = null,
    val segments: List<RowSegment> = emptyList(), val pendingReduction: Boolean = false, val workerId: Int? = null,
    /** Non-fatal note: a successful job's rsync warning, or a worker that is reconnecting. */
    val warning: String? = null)

/** Rows of one view built lazily: a frame materializes only the visible page. */
internal class RowList(val size: Int, private val keyAt: (Int) -> String, private val rowAt: (Int) -> MonitorRow,
    private val find: (String) -> Int) {
    fun key(index: Int): String = keyAt(index)
    fun row(index: Int): MonitorRow = rowAt(index)
    fun indexOf(key: String): Int = find(key)
}

private val LIVE_WORKERS = setOf(WorkerStatus.CONNECTING, WorkerStatus.IDLE, WorkerStatus.TRANSFERRING)
private const val MESSAGE_NANOS = 5_000_000_000L

class MonitorModel {
    private val dismissedFailedWorkers = mutableSetOf<Int>()
    internal fun visibleWorkers(snapshot: AirliftSnapshot) = snapshot.workers.filter {
        it.status != WorkerStatus.STOPPED && !(it.status == WorkerStatus.FAILED && it.id in dismissedFailedWorkers)
    }
    internal data class MouseRow(val x: Int, val y: Int, val width: Int, val index: Int)
    internal val mouseRows = mutableListOf<MouseRow>()
    internal var bodyBottom = 9

    internal fun handleMouse(action: MonitorEvent.Mouse, snapshot: AirliftSnapshot) {
        val x = action.x
        val y = action.y
        when (action.actionType) {
            MonitorMouseAction.CLICK_DOWN -> if (action.button == 1) {
                if (y == 7) {
                    val tab = when (x) {
                        in 0 until 10 -> MonitorView.WORKERS
                        in 13 until 21 -> MonitorView.QUEUE
                        in 24 until 33 -> MonitorView.ERRORS
                        else -> null
                    }
                    if (tab != null) { view = tab; selected = 0; mouseRows.clear() }
                } else mouseRows.firstOrNull { y == it.y && x >= it.x && x < it.x + it.width }?.let { selected = it.index }
            }
            MonitorMouseAction.SCROLL_UP, MonitorMouseAction.SCROLL_DOWN -> if (y in 9 until bodyBottom) {
                val step = if (action.actionType == MonitorMouseAction.SCROLL_UP) -1 else 1
                selected = (selected + step).coerceIn(0, (rowList(snapshot).size - 1).coerceAtLeast(0))
            }
            else -> Unit
        }
    }
    var view = MonitorView.WORKERS
    private var selectedIndex = 0
    /** The selected row's stable key; the index is re-resolved from it on every frame. */
    private var selectedKey: String? = null
    var selected: Int
        get() = selectedIndex
        set(value) { selectedIndex = value; selectedKey = null }
    var details = true
    var message = ""
        set(value) { field = value; messageShownAt = null }
    private var messageShownAt: Long? = null
    /** Connection problems of an attached source; shown instead of [message] while set. */
    internal var connectionStatus: String? = null
    var gridRows = 1
    var pageCapacity = 1

    /** Clears [message] a few seconds after it was first presented. */
    internal fun expireMessage(now: Long) {
        if (message.isEmpty()) return
        val shown = messageShownAt ?: now.also { messageShownAt = it }
        if (now - shown >= MESSAGE_NANOS) message = ""
    }

    /** Keeps the selected key when rows move; falls back to the previous position when it disappeared. */
    internal fun resolveSelection(rows: RowList) {
        val found = selectedKey?.let(rows::indexOf)?.takeIf { it >= 0 }
        selectedIndex = (found ?: selectedIndex).coerceIn(0, (rows.size - 1).coerceAtLeast(0))
        selectedKey = if (rows.size > 0) rows.key(selectedIndex) else null
    }

    fun rows(snapshot: AirliftSnapshot, cellWidth: Int = 55): List<MonitorRow> =
        rowList(snapshot, cellWidth).let { list -> List(list.size, list::row) }

    private var cachedRows: RowList? = null
    private var cachedSnapshot: AirliftSnapshot? = null
    private var cachedKey: Triple<MonitorView, Int, Int>? = null

    internal fun rowList(snapshot: AirliftSnapshot, cellWidth: Int = 55): RowList {
        // Identity of the snapshot plus every input of buildRows; only worker rows depend on the cell width.
        val key = Triple(view, if (view == MonitorView.WORKERS) cellWidth else 0, dismissedFailedWorkers.size)
        cachedRows?.let { if (cachedSnapshot === snapshot && cachedKey == key) return it }
        return buildRows(snapshot, cellWidth).also { cachedRows = it; cachedSnapshot = snapshot; cachedKey = key }
    }

    private fun buildRows(snapshot: AirliftSnapshot, cellWidth: Int): RowList = when (view) {
        MonitorView.WORKERS -> {
            val workers = visibleWorkers(snapshot).sortedBy { it.id }
            // Look up only the tasks referenced by visible workers rather than indexing the whole ledger.
            val wanted = workers.mapNotNullTo(HashSet()) { it.taskId }
            val tasks = HashMap<String, TaskState>(wanted.size * 2)
            if (wanted.isNotEmpty()) for (task in snapshot.tasks) if (task.task.id in wanted) tasks[task.task.id] = task
            val live = snapshot.workers.filter { it.status in LIVE_WORKERS }.sortedBy { it.id }
            // A visual quota only: the dispatcher still retires the first workers to finish.
            val reduction = live.takeLast((live.size - snapshot.targetWorkers).coerceIn(0, live.size)).map { it.id }.toSet()
            RowList(workers.size, { "worker-${workers[it].id}" }, { index ->
                val worker = workers[index]
                val task = worker.taskId?.let(tasks::get)
                val segments = workerSegments(worker, task, cellWidth)
                // A worker error is fatal only for a FAILED worker; a reconnecting worker carries a note.
                val failed = worker.status == WorkerStatus.FAILED
                MonitorRow("worker-${worker.id}", segments.joinToString("") { it.text }, task,
                    (if (failed) worker.error else null) ?: task?.error, segments,
                    pendingReduction = worker.id in reduction, workerId = worker.id,
                    warning = (if (failed) null else worker.error) ?: task?.warning)
            }, { key -> workers.indexOfFirst { "worker-${it.id}" == key } })
        }
        MonitorView.QUEUE -> {
            val tasks = snapshot.tasks.filter { it.status == TaskStatus.QUEUED || it.status == TaskStatus.RUNNING }
            RowList(tasks.size, { tasks[it].task.id }, { index ->
                val task = tasks[index]
                MonitorRow(task.task.id, "${task.status.toString().padEnd(10)} ${bytes(task.task.size).padStart(8)} ${task.task.path}", task)
            }, { key -> tasks.indexOfFirst { it.task.id == key } })
        }
        MonitorView.ERRORS -> {
            // Failures first, then worker problems, then warnings of otherwise successful jobs.
            val failures = snapshot.tasks.filter { it.error != null }
            val workers = snapshot.workers.filter { it.error != null }
            val warnings = snapshot.tasks.filter { it.error == null && it.warning != null }
            fun workerKey(worker: WorkerState) = "worker-${worker.id}"
            RowList(failures.size + workers.size + warnings.size, { index ->
                when {
                    index < failures.size -> failures[index].task.id
                    index < failures.size + workers.size -> workerKey(workers[index - failures.size])
                    else -> warnings[index - failures.size - workers.size].task.id
                }
            }, { index ->
                when {
                    index < failures.size -> failures[index].let {
                        MonitorRow(it.task.id, "${it.status} attempt ${it.attempts}: ${it.task.path} | ${it.error}", it, it.error)
                    }
                    index < failures.size + workers.size -> workers[index - failures.size].let {
                        if (it.status == WorkerStatus.FAILED) MonitorRow(workerKey(it), "SSH worker ${it.id}: ${it.error}", error = it.error, workerId = it.id)
                        else MonitorRow(workerKey(it), "SSH worker ${it.id} ${it.status}: ${it.error}", workerId = it.id, warning = it.error)
                    }
                    else -> warnings[index - failures.size - workers.size].let {
                        MonitorRow(it.task.id, "WARNING attempt ${it.attempts}: ${it.task.path} | ${it.warning}", it, warning = it.warning)
                    }
                }
            }, { key ->
                failures.indexOfFirst { it.task.id == key }.takeIf { it >= 0 }
                    ?: workers.indexOfFirst { workerKey(it) == key }.takeIf { it >= 0 }?.plus(failures.size)
                    ?: warnings.indexOfFirst { it.task.id == key }.takeIf { it >= 0 }?.plus(failures.size + workers.size)
                    ?: -1
            })
        }
    }

    suspend fun handle(command: Char, source: MonitorSource) {
        when (command.lowercaseChar()) {
            '1' -> { view = MonitorView.WORKERS; selected = 0 }
            '2' -> { view = MonitorView.QUEUE; selected = 0 }
            '3' -> { view = MonitorView.ERRORS; selected = 0 }
            'p', '+', '=', '-' -> control(command.lowercaseChar(), source)
        }
    }

    /** Commands that change the dispatcher decide from a fresh snapshot, never from a frame that may be stale. */
    private suspend fun control(command: Char, source: MonitorSource) {
        val snapshot = source.snapshot()
        // A failed connection leaves targetWorkers above the live pool. Do not
        // backfill that entire gap when the user asks for a single extra worker.
        val live = snapshot.workers.count { it.status in LIVE_WORKERS }
        val adjustableWorkers = if (snapshot.workers.any { it.status == WorkerStatus.FAILED })
            minOf(snapshot.targetWorkers, live) else snapshot.targetWorkers
        when (command) {
            'p' -> if (snapshot.finishedAtMillis == null) {
                if (snapshot.paused) source.resume() else source.pause()
            }
            '+', '=' -> if (snapshot.finishedAtMillis == null) {
                check(adjustableWorkers < AirliftConfig.MAX_WORKERS) { "Workers are limited to ${AirliftConfig.MAX_WORKERS}" }
                source.setWorkerCount(adjustableWorkers + 1)
            }
            '-' -> {
                val failed = visibleWorkers(snapshot).filter { it.status == WorkerStatus.FAILED }.minByOrNull { it.id }
                if (failed != null) {
                    dismissedFailedWorkers.add(failed.id)
                    mouseRows.clear()
                } else if (snapshot.finishedAtMillis == null) source.setWorkerCount((adjustableWorkers - 1).coerceIn(1, AirliftConfig.MAX_WORKERS))
            }
        }
    }

    companion object {
        fun compactDuration(seconds: Long?): String {
            if (seconds == null) return "--"
            val value = seconds.coerceAtLeast(0)
            return when {
                value >= 1209600 -> "${value / 604800}w"
                value >= 86400 -> "${value / 86400}d"
                value >= 3600 -> "${value / 3600}h"
                value >= 60 -> "${value / 60}m"
                else -> "${value}s"
            }
        }

        fun workerSegments(worker: WorkerState, task: TaskState?, width: Int = 55): List<RowSegment> =
            WorkerLineLayout(width).segments(worker, task)

        /** Completed attempts consume their whole weight, including final failures, but not cancellations. */
        fun overallProgress(snapshot: AirliftSnapshot): Double = MonitorStats.of(snapshot).progress

        private val UNITS = arrayOf("B", "KiB", "MiB", "GiB", "TiB", "PiB")
        fun bytes(value: Long?): String {
            if (value == null) return "?"
            var number = value.toDouble()
            var unit = 0
            while (number >= 1024 && unit < UNITS.lastIndex) { number /= 1024; unit++ }
            return fixedOne(number) + UNITS[unit]
        }
        fun duration(seconds: Long?): String = if (seconds == null) "--:--:--" else
            listOf(seconds / 3600, seconds / 60 % 60, seconds % 60).joinToString(":") { it.toString().padStart(2, '0') }
        /** Data reached the displayed total, but the transport has not reported its exit yet. */
        fun finishing(task: TaskState?): Boolean =
            task?.status == TaskStatus.RUNNING && percent(task).trim() == "100%"

        fun percent(task: TaskState): String {
            val size = task.task.size
            val progress = task.progress
            return when {
                task.status == TaskStatus.SUCCEEDED -> "100%"
                progress == null -> " --%"
                size == null || size == 0L -> progress.percent?.let { it.toString().padStart(3) + "%" } ?: " --%"
                else -> (100.0 * progress.logicalBytes / size).toInt().coerceIn(0, 100).toString().padStart(3) + "%"
            }
        }
        fun freshRate(task: TaskState, now: Long = monitorMonotonicNanos()): Double? =
            if (task.status == TaskStatus.RUNNING && task.progressUpdatedAtNanos?.let { now - it < 3_000_000_000 } == true)
                task.progress?.bytesPerSecond else null
        fun rate(task: TaskState): String {
            val speed = if (task.status == TaskStatus.SUCCEEDED) task.progress?.bytesPerSecond else freshRate(task)
            return speed?.let { bytes(it.toLong()) + "/s" } ?: "--/s"
        }
    }
}

/** Snapshot-wide aggregates computed in one pass per frame instead of one pass per header field. */
internal class MonitorStats private constructor(
    val allSizesKnown: Boolean, val totalBytes: Long, val processedBytes: Long,
    val totalFiles: Int, val completedFiles: Int, val failedFiles: Int, val warnings: Int,
    val running: List<TaskState>, val progress: Double,
) {
    companion object {
        fun of(snapshot: AirliftSnapshot): MonitorStats {
            var allKnown = true
            var total = 0L
            var processed = 0L
            var files = 0; var completed = 0; var failed = 0; var warnings = 0
            var doneBytes = 0.0; var doneCount = 0.0
            val running = ArrayList<TaskState>()
            for (state in snapshot.tasks) {
                val size = state.task.size
                if (size == null) allKnown = false else total += size
                val count = state.task.fileCount
                files += count
                if (state.warning != null) warnings++
                val fraction = when (state.status) {
                    TaskStatus.SUCCEEDED -> {
                        completed += count
                        processed += size ?: state.progress?.logicalBytes ?: 0
                        1.0
                    }
                    TaskStatus.FAILED -> { failed += count; 1.0 }
                    TaskStatus.RUNNING -> {
                        running += state
                        val logical = state.progress?.logicalBytes ?: 0
                        processed += size?.let { logical.coerceAtMost(it) } ?: logical
                        if (size != null && size > 0) logical.toDouble() / size else (state.progress?.percent ?: 0) / 100.0
                    }
                    else -> 0.0
                }.coerceIn(0.0, 1.0)
                if (size != null) doneBytes += size.toDouble() * fraction
                doneCount += fraction
            }
            val progress = when {
                snapshot.tasks.isEmpty() -> 1.0
                allKnown && total > 0 -> doneBytes / total
                else -> doneCount / snapshot.tasks.size
            }.coerceIn(0.0, 1.0)
            return MonitorStats(allKnown, total, processed, files, completed, failed, warnings, running, progress)
        }
    }
}
