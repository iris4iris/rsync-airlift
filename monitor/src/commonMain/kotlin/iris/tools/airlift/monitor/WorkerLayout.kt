package iris.tools.airlift.monitor

import iris.tools.airlift.*
import kotlin.math.ceil

/** Shared field widths for headers and values, including cells in a multi-column grid. */
data class WorkerLineLayout(val width: Int) {
    val barWidth = (width - 39).coerceAtLeast(1)
    fun tail(percent: String, size: String, speed: String, eta: String) =
        " ${percent.padStart(4)} ${size.padStart(9)} ${speed.padStart(12)} ${eta.padStart(4)}"
    fun header() = "ID  " + "PROGRESS".padEnd(barWidth + 2) + tail("%", "SIZE", "SPEED", "ETA")

    fun segments(worker: WorkerState, task: TaskState?): List<RowSegment> {
        val percent = task?.let(MonitorModel::percent) ?: " --%"
        val fraction = percent.trim().removeSuffix("%").toIntOrNull()?.coerceIn(0, 100) ?: 0
        val fill = fraction * barWidth / 100
        val failed = worker.status == WorkerStatus.FAILED || task?.status == TaskStatus.FAILED
        val finishing = MonitorModel.finishing(task) && worker.status == WorkerStatus.TRANSFERRING
        val preparing = !finishing && task?.status == TaskStatus.RUNNING && worker.status == WorkerStatus.TRANSFERRING &&
            (task.progress?.logicalBytes ?: 0) == 0L && (task.progress?.bytesPerSecond ?: 0.0) <= 0.0
        val label = when {
            failed -> "FAILED"
            worker.status == WorkerStatus.CONNECTING -> "CONNECTING"
            worker.status == WorkerStatus.STOPPED -> "STOPPED"
            finishing -> " finishing"
            preparing -> {
                if (barWidth >= " computing checksums".length) " computing checksums" else " checksums"
            }
            task == null -> "IDLE"
            else -> ""
        }
        val bar = if (label.isNotEmpty()) label.take(barWidth).padEnd(barWidth)
            else "|".repeat(fill) + " ".repeat(barWidth - fill)
        val eta = when (task?.status) {
            TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.CANCELLED -> ""
            TaskStatus.RUNNING -> if (preparing || finishing) "" else MonitorModel.compactDuration(task.progress?.remainingSeconds)
            else -> "--"
        }
        val statsProgress = task?.progress
        val barSegments = if (finishing && !failed) {
            val caption = label.take(barWidth)
            listOf(RowSegment(caption, MonitorColor.ANSI.WHITE),
                RowSegment("|".repeat(barWidth - caption.length), MonitorColor.ANSI.GREEN))
        } else if (label.isEmpty() && task?.status == TaskStatus.SUCCEEDED && statsProgress?.matchedBytes != null && statsProgress.literalBytes != null) {
            val literal = statsProgress.literalBytes!!.coerceAtLeast(0)
            val rawMatched = statsProgress.matchedBytes!!.coerceAtLeast(0)
            val total = task.task.size ?: (rawMatched + literal)
            val matched = if (task.task.isBatch || rawMatched + literal == 0L) (total - literal).coerceAtLeast(rawMatched) else rawMatched
            val matchedWidth = if (total > 0) (barWidth * (matched.toDouble() / maxOf(total.toDouble(), matched.toDouble() + literal))).toInt().coerceIn(0, barWidth) else barWidth
            listOf(RowSegment("|".repeat(matchedWidth), MonitorColor.ANSI.CYAN), RowSegment("|".repeat(barWidth - matchedWidth), MonitorColor.ANSI.GREEN))
        } else listOf(RowSegment(bar, when {
                failed -> MonitorColor.ANSI.RED
                preparing -> MonitorColor.ANSI.WHITE
                else -> MonitorColor.ANSI.GREEN
            }))
        return listOf(RowSegment(worker.id.toString().padStart(3, '0') + " [")) + barSegments + listOf(
            RowSegment("]" + tail(percent, MonitorModel.bytes(task?.task?.size), task?.let(MonitorModel::rate) ?: "--/s", eta)),
        )
    }
}

data class WorkerGrid(val width: Int, val count: Int, val availableRows: Int) {
    val columns = minOf(
        ceil(count.coerceAtLeast(1).toDouble() / availableRows.coerceAtLeast(1)).toInt(),
        ((width + GAP) / (MIN_WIDTH + GAP)).coerceAtLeast(1),
    )
    val cellWidth = ((width - (columns - 1) * GAP) / columns).coerceAtMost(88)
    val rows = count.coerceAtLeast(1).coerceAtMost(availableRows.coerceAtLeast(1))
    val capacity = rows * columns
    fun position(index: Int) = Pair((index % capacity / rows) * (cellWidth + GAP), index % rows)
    companion object { const val MIN_WIDTH = 52; const val GAP = 3 }
}
