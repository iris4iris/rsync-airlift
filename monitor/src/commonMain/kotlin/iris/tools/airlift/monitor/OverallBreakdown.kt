package iris.tools.airlift.monitor

import iris.tools.airlift.*

/** Derived from the full task ledger, never from just the files currently assigned to workers. */
data class OverallBreakdown(
    val matched: Double, val literal: Double, val failed: Double, val other: Double,
    val matchedBytes: Long, val literalBytes: Long,
    val processing: Double = 0.0,
    val skipped: Double = 0.0, val skippedBytes: Long = 0,
) {
    fun summary(processedBytes: Long): String {
        fun portion(value: Long): String = fixedOne(
            if (processedBytes > 0) (100.0 * value / processedBytes).coerceIn(0.0, 100.0) else 0.0) + "%"
        return "Skipped ${MonitorModel.bytes(skippedBytes)} (${portion(skippedBytes)}) | Matched ${MonitorModel.bytes(matchedBytes)} (${portion(matchedBytes)}) | New ${MonitorModel.bytes(literalBytes)} (${portion(literalBytes)})"
    }

    fun segments(width: Int): List<RowSegment> {
        val fractions = listOf(skipped, matched, literal + processing, failed, other)
        val colors = listOf(MonitorColor.ANSI.BLUE, MonitorColor.ANSI.CYAN, MonitorColor.ANSI.GREEN, MonitorColor.ANSI.RED, MonitorColor.ANSI.YELLOW)
        var cumulative = 0.0
        var assigned = 0
        val result = fractions.mapIndexed { index, fraction ->
            cumulative += fraction
            val boundary = (cumulative.coerceIn(0.0, 1.0) * width + 1e-8).toInt().coerceAtMost(width)
            RowSegment("|".repeat((boundary - assigned).coerceAtLeast(0)), colors[index]).also { assigned = boundary }
        }
        return result + RowSegment(" ".repeat((width - assigned).coerceAtLeast(0)))
    }

    companion object {
        fun from(snapshot: AirliftSnapshot): OverallBreakdown {
            val totalSize = snapshot.tasks.sumOf { it.task.size ?: 0 }.toDouble()
            val byBytes = totalSize > 0 && snapshot.tasks.all { it.task.size != null }
            val denominator = if (byBytes) totalSize else snapshot.tasks.size.toDouble().coerceAtLeast(1.0)
            var skipped = 0.0
            var skippedBytes = 0L
            var matched = 0.0
            var literal = 0.0
            var failed = 0.0
            var activeRemaining = 0.0
            var matchedBytes = 0L
            var literalBytes = 0L
            for (task in snapshot.tasks) {
                val weight = (if (byBytes) task.task.size!!.toDouble() else 1.0) / denominator
                if (task.status == TaskStatus.FAILED) { failed += weight; continue }
                if (task.status == TaskStatus.RUNNING) {
                    val size = task.task.size
                    val fraction = if (size != null && size > 0)
                        (task.progress?.logicalBytes ?: 0).toDouble() / size
                    else (task.progress?.percent ?: 0) / 100.0
                    activeRemaining += weight * (1.0 - fraction.coerceIn(0.0, 1.0))
                    continue
                }
                if (task.status != TaskStatus.SUCCEEDED) continue
                val rawMatched = task.progress?.matchedBytes
                val l = task.progress?.literalBytes
                if (rawMatched == null || l == null || rawMatched < 0 || l < 0) continue
                val m = rawMatched
                // Successful bytes outside rsync's delta counters required no transfer.
                // Filters and dry-run options can also account for this remainder.
                val skippedSize = task.task.size?.let { (it - m - l).coerceAtLeast(0) } ?: 0
                skippedBytes += skippedSize
                matchedBytes += m
                literalBytes += l
                val sum = m.toDouble() + l + skippedSize
                val basis = if (byBytes) task.task.size!!.toDouble() else sum
                if (basis <= 0) continue
                // Never paint more than this task's weight, even if its source changed size.
                val divisor = maxOf(basis, sum)
                skipped += weight * skippedSize / divisor
                matched += weight * m / divisor
                literal += weight * l / divisor
            }
            val processed = MonitorModel.overallProgress(snapshot)
            // Green includes logical progress awaiting final Matched/New statistics.
            // Orange reserves the still-unprocessed part of all active jobs.
            val processing = (processed - skipped - matched - literal - failed).coerceAtLeast(0.0)
            return OverallBreakdown(matched, literal, failed, activeRemaining, matchedBytes, literalBytes, processing, skipped, skippedBytes)
        }
    }
}
