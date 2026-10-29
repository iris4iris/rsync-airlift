package iris.tools.airlift.monitor

import iris.tools.airlift.*

/** Derived from the full task ledger, never from just the files currently assigned to workers. */
data class OverallBreakdown(
    val matched: Double, val literal: Double, val failed: Double, val other: Double,
    val matchedBytes: Long, val literalBytes: Long,
    val processing: Double = 0.0,
) {
    fun summary(processedBytes: Long): String {
        fun portion(value: Long): String = fixedOne(
            if (processedBytes > 0) (100.0 * value / processedBytes).coerceIn(0.0, 100.0) else 0.0) + "%"
        return "Matched ${MonitorModel.bytes(matchedBytes)} (${portion(matchedBytes)}) | New ${MonitorModel.bytes(literalBytes)} (${portion(literalBytes)})"
    }

    fun segments(width: Int): List<RowSegment> {
        val fractions = listOf(matched, literal + processing, failed, other)
        val colors = listOf(MonitorColor.ANSI.CYAN, MonitorColor.ANSI.GREEN, MonitorColor.ANSI.RED, MonitorColor.ANSI.YELLOW)
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
        fun from(snapshot: AirliftSnapshot): OverallBreakdown = from(snapshot, MonitorStats.of(snapshot))

        internal fun from(snapshot: AirliftSnapshot, stats: MonitorStats): OverallBreakdown {
            val totalSize = stats.totalBytes.toDouble()
            val byBytes = totalSize > 0 && stats.allSizesKnown
            val denominator = if (byBytes) totalSize else snapshot.tasks.size.toDouble().coerceAtLeast(1.0)
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
                val m = if (task.task.isBatch && task.task.size != null)
                    rawMatched + (requireNotNull(task.task.size) - rawMatched - l).coerceAtLeast(0) else rawMatched
                // UI Matched includes successful files requiring no data transfer;
                // this describes avoided transfer, not a checksum verification.
                if (m == 0L && l == 0L) {
                    matched += weight
                    matchedBytes += task.task.size ?: 0
                    continue
                }
                matchedBytes += m
                literalBytes += l
                val sum = m.toDouble() + l
                val basis = if (byBytes) task.task.size!!.toDouble() else sum
                if (basis <= 0) continue
                // Never paint more than this task's weight, even if its source changed size.
                val divisor = maxOf(basis, sum)
                matched += weight * m / divisor
                literal += weight * l / divisor
            }
            val processed = stats.progress
            // Green includes logical progress awaiting final Matched/New statistics.
            // Orange reserves the still-unprocessed part of all active jobs.
            val processing = (processed - matched - literal - failed).coerceAtLeast(0.0)
            return OverallBreakdown(matched, literal, failed, activeRemaining, matchedBytes, literalBytes, processing)
        }
    }
}
