package iris.tools.airlift.monitor

import iris.tools.airlift.*
import kotlin.math.ceil
import iris.tools.airlift.monitor.MonitorModel.Companion.bytes
import iris.tools.airlift.monitor.MonitorModel.Companion.duration

class MonitorRenderer(private val source: MonitorSource, private val title: String = source.title) {
    private val overallRate = OverallRate()
    /** Builds a frame without any terminal-library types. */
    fun render(size: MonitorSize, snapshot: AirliftSnapshot, model: MonitorModel): MonitorFrame {
        val screen = MonitorFrame(size)
        model.mouseRows.clear()
        model.bodyBottom = 9
        val width = size.columns
        val height = size.rows
        val graphics = screen
        // Reset only the back buffer. Screen.clear() also requests a physical clear on refresh.
        graphics.foregroundColor = MonitorColor.ANSI.WHITE
        graphics.backgroundColor = MonitorColor.ANSI.BLACK
        fun line(y: Int, text: String, color: MonitorColor = MonitorColor.ANSI.WHITE, selected: Boolean = false) {
            if (y !in 0 until height || width <= 0) return
            graphics.foregroundColor = color
            graphics.backgroundColor = if (selected) MonitorColor.ANSI.BLUE else MonitorColor.ANSI.BLACK
            graphics.putString(0, y, text, width, pad = true)
        }
        fun segments(x: Int, y: Int, parts: List<RowSegment>, limit: Int, selected: Boolean = false, reduction: Boolean = false) {
            graphics.backgroundColor = when {
                reduction && selected -> MonitorColor.RGB(165, 112, 62)
                reduction -> MonitorColor.RGB(135, 88, 44)
                selected -> MonitorColor.ANSI.BLUE
                else -> MonitorColor.ANSI.BLACK
            }
            var used = 0
            for (part in parts) {
                if (used >= limit) break
                val text = part.text.take(limit - used)
                graphics.foregroundColor = if (reduction) MonitorColor.ANSI.BLACK else part.color
                graphics.putString(x + used, y, text)
                used += text.length
            }
            graphics.foregroundColor = if (reduction) MonitorColor.ANSI.BLACK else MonitorColor.ANSI.WHITE
            if (used < limit) graphics.putString(x + used, y, " ".repeat(limit - used))
        }
        if (width < 64 || height < 16) {
            line(0, title, MonitorColor.ANSI.CYAN)
            line(2, "Resize terminal to at least 64 x 16.")
            line(height - 1, if (snapshot.finishedAtMillis == null) "Q Detach (transfers continue)" else "Q Exit")
            return screen
        }
        val state = when {
            snapshot.cancelled -> "CANCELLED"
            snapshot.finishedAtMillis != null -> if (snapshot.failed > 0) "FINISHED WITH ERRORS" else "FINISHED"
            snapshot.paused -> "PAUSED (active files continue)"
            snapshot.startedAtMillis == null -> "NOT STARTED"
            else -> "RUNNING"
        }
        val elapsed = snapshot.startedAtMillis?.let { ((snapshot.finishedAtMillis ?: monitorWallTimeMillis()) - it).coerceAtLeast(0) / 1000 }
        val rate = overallRate.value(snapshot)
        val allKnown = snapshot.tasks.all { it.task.size != null }
        val total = snapshot.tasks.sumOf { it.task.size ?: 0 }
        val processed = snapshot.tasks.sumOf {
            if (it.status == TaskStatus.SUCCEEDED) it.task.size ?: it.progress?.logicalBytes ?: 0
            else if (it.status == TaskStatus.RUNNING) (it.progress?.logicalBytes ?: 0).let { count -> it.task.size?.let { size -> count.coerceAtMost(size) } ?: count }
            else 0
        }
        val calculatedEta = if (allKnown && rate != null && rate > 0)
            ceil((total - processed).coerceAtLeast(0) / rate).toLong() else null
        val longestWorkerEta = snapshot.tasks.asSequence()
            .filter { it.status == TaskStatus.RUNNING && !MonitorModel.finishing(it) }
            .mapNotNull { it.progress?.remainingSeconds?.takeIf { seconds -> seconds >= 0 } }
            .maxOrNull()
        val eta = if (snapshot.failed == 0 && !snapshot.paused)
            listOfNotNull(calculatedEta, longestWorkerEta).maxOrNull() else null
        val breakdown = OverallBreakdown.from(snapshot)
        val finished = snapshot.finishedAtMillis != null
        val displayRate = if (finished) snapshot.startedAtMillis?.let { start ->
            val elapsedMillis = requireNotNull(snapshot.finishedAtMillis) - start
            if (elapsedMillis > 0) (processed - breakdown.skippedBytes).coerceAtLeast(0).toDouble() * 1000 / elapsedMillis else null
        } else rate
        val etaText = if (finished) "" else " | ETA ${duration(eta)}"
        val ratio = MonitorModel.overallProgress(snapshot)
        line(0, "$title | $state", MonitorColor.ANSI.CYAN)
        line(1, "From: ${source.from}")
        line(2, "To:   ${source.to}")
        val workerCount = if (finished) snapshot.targetWorkers else snapshot.workers.count {
            it.status in setOf(WorkerStatus.CONNECTING, WorkerStatus.IDLE, WorkerStatus.TRANSFERRING)
        }
        val failedText = if (snapshot.failed > 0) " | Failed ${snapshot.failed}" else ""
        line(3, "Workers $workerCount | Files ${snapshot.completed}/${snapshot.tasks.sumOf { it.task.fileCount }}$failedText | Elapsed ${duration(elapsed)}")
        line(4, "Processed ${bytes(processed)} / ${if (allKnown) bytes(total) else "unknown total"} | ${displayRate?.let { bytes(it.toLong()) + "/s" } ?: "--/s"}$etaText")
        val barWidth = (width - 10).coerceAtMost(100)
        segments(0, 5, listOf(RowSegment("[")) + breakdown.segments(barWidth) +
            RowSegment("] ${fixedOne(ratio * 100) + "%"}"), width)
        line(6, breakdown.summary(processed))
        line(7, "1: Workers | 2: Queue | 3: Errors | Tab: switch | ${model.view}", MonitorColor.ANSI.CYAN)
        val detailHeight = if (model.details && height >= 22) 4 else 0
        val rowCount = (height - 11 - detailHeight).coerceAtLeast(1)
        model.bodyBottom = 9 + rowCount
        val grid = WorkerGrid(width, model.visibleWorkers(snapshot).size, rowCount)
        val rows = model.rows(snapshot, grid.cellWidth)
        model.selected = model.selected.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
        val workersView = model.view == MonitorView.WORKERS
        model.gridRows = if (workersView) grid.rows else 1
        model.pageCapacity = if (workersView) grid.capacity else rowCount
        if (workersView) {
            val layout = WorkerLineLayout(grid.cellWidth)
            for (column in 0 until grid.columns) {
                segments(column * (grid.cellWidth + WorkerGrid.GAP), 8,
                    listOf(RowSegment(layout.header(), MonitorColor.ANSI.CYAN)), grid.cellWidth)
            }
        }
        val offset = if (workersView) model.selected / grid.capacity * grid.capacity else (model.selected - rowCount + 1).coerceAtLeast(0)
        if (rows.isEmpty()) line(9, "No entries.")
        if (workersView) {
            rows.drop(offset).take(grid.capacity).forEachIndexed { index, row ->
                val (x, y) = grid.position(index)
                segments(x, 9 + y, row.segments, grid.cellWidth, offset + index == model.selected, row.pendingReduction)
                model.mouseRows.add(MonitorModel.MouseRow(x, 9 + y, grid.cellWidth, offset + index))
            }
        } else {
        rows.drop(offset).take(rowCount).forEachIndexed { index, row ->
            val y = 9 + index
            val selected = offset + index == model.selected
            line(y, row.text, if (row.error != null) MonitorColor.ANSI.RED else MonitorColor.ANSI.WHITE, selected)
            model.mouseRows.add(MonitorModel.MouseRow(0, y, width, offset + index))
        }
        }
        if (detailHeight > 0) {
            val row = rows.getOrNull(model.selected)
            val task = row?.task
            val y = height - 6
            val workerId = row?.workerId ?: snapshot.workers.firstOrNull { task != null && it.taskId == task.task.id }?.id
            val worker = snapshot.workers.firstOrNull { it.id == workerId }
            line(y, "DETAILS WORKER${workerId?.let { " " + it.toString().padStart(3, '0') } ?: ""}", MonitorColor.ANSI.CYAN)
            val currentName = task?.progress?.currentFile ?: if ((task?.task?.fileCount ?: 1) > 1) "awaiting rsync filename" else task?.task?.path ?: "--"
            line(y + 1, "File: $currentName")
            val batchInfo = if ((task?.task?.fileCount ?: 1) > 1) {
                val index = task!!.task.files.indexOf(task.progress?.currentFile).takeIf { it >= 0 }?.plus(1)
                val position = if (task.status == TaskStatus.SUCCEEDED) "Processed ${task.task.fileCount}/${task.task.fileCount}"
                    else "file ${index ?: "--"}/${task.task.fileCount}"
                " | Files ${task.task.fileCount} | $position | sent ${task.progress?.transferredFiles ?: 0}"
            } else ""
            val displayedStatus = if (MonitorModel.finishing(task)) "FINISHING" else task?.status ?: worker?.status ?: "--"
            val statusInfo = "$displayedStatus$batchInfo | Size ${bytes(task?.task?.size)}" +
                " | Speed ${task?.let { MonitorModel.rate(it) } ?: "--/s"} | attempt ${task?.attempts ?: 0}"
            line(y + 2, statusInfo)
            val error = task?.error ?: row?.error
            line(y + 3, error?.let { "Error: $it" } ?: "", MonitorColor.ANSI.RED)
        }
        segments(0, height - 2, if (model.message.isNotEmpty()) listOf(RowSegment(model.message, MonitorColor.ANSI.YELLOW)) else listOf(
            RowSegment("Overall: "), RowSegment("skipped ", MonitorColor.ANSI.BLUE), RowSegment("matched ", MonitorColor.ANSI.CYAN), RowSegment("new/processed ", MonitorColor.ANSI.GREEN),
            RowSegment("failed ", MonitorColor.ANSI.RED), RowSegment("active remaining", MonitorColor.ANSI.YELLOW)), width)
        line(height - 1, "Arrows Select | F2 Details | P Pause | +/- Workers | Q ${if (snapshot.finishedAtMillis == null) "Detach" else "Exit"}", MonitorColor.ANSI.CYAN)
        return screen
    }
}
