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
                // Clip to the cell so wide characters can never spill into a neighbouring column.
                graphics.putString(x + used, y, text, limit - used)
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
        val stats = MonitorStats.of(snapshot)
        val state = when {
            snapshot.cancelled -> "CANCELLED"
            snapshot.finishedAtMillis != null -> when {
                stats.failedFiles > 0 -> "FINISHED WITH ERRORS"
                stats.warnings > 0 -> "FINISHED WITH WARNINGS"
                else -> "FINISHED"
            }
            snapshot.paused -> "PAUSED (active files continue)"
            snapshot.startedAtMillis == null -> "NOT STARTED"
            else -> "RUNNING"
        }
        val elapsed = snapshot.startedAtMillis?.let { ((snapshot.finishedAtMillis ?: monitorWallTimeMillis()) - it).coerceAtLeast(0) / 1000 }
        val rate = overallRate.value(snapshot, stats.running)
        val allKnown = stats.allSizesKnown
        val total = stats.totalBytes
        val processed = stats.processedBytes
        val eta = if (allKnown && rate != null && rate > 0 && stats.failedFiles == 0 && !snapshot.paused)
            ceil((total - processed).coerceAtLeast(0) / rate).toLong() else null
        val finished = snapshot.finishedAtMillis != null
        val displayRate = if (finished) snapshot.startedAtMillis?.let { start ->
            val elapsedMillis = requireNotNull(snapshot.finishedAtMillis) - start
            if (elapsedMillis > 0) processed.toDouble() * 1000 / elapsedMillis else null
        } else rate
        val etaText = if (finished) "" else " | ETA ${duration(eta)}"
        val ratio = stats.progress
        line(0, "$title | $state", MonitorColor.ANSI.CYAN)
        line(1, "From: ${source.from}")
        line(2, "To:   ${source.to}")
        val workerCount = if (finished) snapshot.targetWorkers else snapshot.workers.count {
            it.status == WorkerStatus.CONNECTING || it.status == WorkerStatus.IDLE || it.status == WorkerStatus.TRANSFERRING
        }
        val failedText = if (stats.failedFiles > 0) " | Failed ${stats.failedFiles}" else ""
        val warningText = if (stats.warnings > 0) " | Warnings ${stats.warnings}" else ""
        line(3, "Workers $workerCount | Files ${stats.completedFiles}/${stats.totalFiles}$failedText$warningText | Elapsed ${duration(elapsed)}")
        line(4, "Processed ${bytes(processed)} / ${if (allKnown) bytes(total) else "unknown total"} | ${displayRate?.let { bytes(it.toLong()) + "/s" } ?: "--/s"}$etaText")
        val breakdown = OverallBreakdown.from(snapshot, stats)
        val barWidth = (width - 10).coerceAtMost(100)
        segments(0, 5, listOf(RowSegment("[")) + breakdown.segments(barWidth) +
            RowSegment("] ${fixedOne(ratio * 100) + "%"}"), width)
        line(6, breakdown.summary(processed))
        line(7, "1: Workers | 2: Queue | 3: Errors | Tab: switch | ${model.view}", MonitorColor.ANSI.CYAN)
        val detailHeight = if (model.details && height >= 22) 4 else 0
        val rowCount = (height - 11 - detailHeight).coerceAtLeast(1)
        model.bodyBottom = 9 + rowCount
        val workersView = model.view == MonitorView.WORKERS
        val grid = WorkerGrid(width, if (workersView) model.visibleWorkers(snapshot).size else 0, rowCount)
        val rows = model.rowList(snapshot, grid.cellWidth)
        model.resolveSelection(rows)
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
        if (rows.size == 0) line(9, "No entries.")
        // Only the visible page is materialized, however long the queue is.
        if (workersView) {
            for (index in offset until minOf(rows.size, offset + grid.capacity)) {
                val row = rows.row(index)
                val (x, y) = grid.position(index - offset)
                segments(x, 9 + y, row.segments, grid.cellWidth, index == model.selected, row.pendingReduction)
                model.mouseRows.add(MonitorModel.MouseRow(x, 9 + y, grid.cellWidth, index))
            }
        } else {
            for (index in offset until minOf(rows.size, offset + rowCount)) {
                val row = rows.row(index)
                val y = 9 + index - offset
                val color = when {
                    row.error != null -> MonitorColor.ANSI.RED
                    row.warning != null -> MonitorColor.ANSI.YELLOW
                    else -> MonitorColor.ANSI.WHITE
                }
                line(y, row.text, color, index == model.selected)
                model.mouseRows.add(MonitorModel.MouseRow(0, y, width, index))
            }
        }
        if (detailHeight > 0) {
            val row = if (rows.size > 0) rows.row(model.selected) else null
            val task = row?.task
            val y = height - 6
            val workerId = row?.workerId ?: snapshot.workers.firstOrNull { task != null && it.taskId == task.task.id }?.id
            val worker = snapshot.workers.firstOrNull { it.id == workerId }
            line(y, "DETAILS WORKER${workerId?.let { " " + it.toString().padStart(3, '0') } ?: ""}", MonitorColor.ANSI.CYAN)
            val batch = (task?.task?.fileCount ?: 1) > 1
            val currentName = task?.progress?.currentFile ?: if (batch) "awaiting rsync filename" else task?.task?.path ?: "--"
            line(y + 1, "File: $currentName")
            val batchInfo = if (task != null && batch) {
                val position = if (task.status == TaskStatus.SUCCEEDED) "Processed ${task.task.fileCount}/${task.task.fileCount}"
                    else "file ${task.progress?.currentFileIndex ?: "--"}/${task.task.fileCount}"
                " | Files ${task.task.fileCount} | $position | sent ${task.progress?.transferredFiles ?: 0}"
            } else ""
            val displayedStatus = if (MonitorModel.finishing(task)) "FINISHING" else task?.status ?: worker?.status ?: "--"
            val statusInfo = "$displayedStatus$batchInfo | Size ${bytes(task?.task?.size)}" +
                " | Speed ${task?.let { MonitorModel.rate(it) } ?: "--/s"} | attempt ${task?.attempts ?: 0}"
            line(y + 2, statusInfo)
            val error = task?.error ?: row?.error
            val warning = task?.warning ?: row?.warning
            when {
                error != null -> line(y + 3, "Error: $error", MonitorColor.ANSI.RED)
                warning != null -> line(y + 3, "Warning: $warning", MonitorColor.ANSI.YELLOW)
                else -> line(y + 3, "")
            }
        }
        val notice = model.connectionStatus ?: model.message
        segments(0, height - 2, if (notice.isNotEmpty()) listOf(RowSegment(notice, MonitorColor.ANSI.YELLOW)) else listOf(
            RowSegment("Overall: "), RowSegment("matched ", MonitorColor.ANSI.CYAN), RowSegment("new/processed ", MonitorColor.ANSI.GREEN),
            RowSegment("failed ", MonitorColor.ANSI.RED), RowSegment("active remaining", MonitorColor.ANSI.YELLOW)), width)
        line(height - 1, "Arrows Select | F2 Details | P Pause | +/- Workers | Q ${if (snapshot.finishedAtMillis == null) "Detach" else "Exit"}", MonitorColor.ANSI.CYAN)
        return screen
    }
}
