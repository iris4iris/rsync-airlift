package iris.tools.airlift.monitor

import iris.tools.airlift.TransferTask

/** Fill until the minimum byte load is reached, or the queue/source root ends. */
internal fun batchSmallFiles(tasks: List<TransferTask>, minBytes: Long = 5L * 1024 * 1024): List<TransferTask> {
    require(minBytes > 0)
    val result = mutableListOf<TransferTask>()
    val pending = mutableListOf<TransferTask>()
    var bytes = 0L
    fun flush() {
        if (pending.isEmpty()) return
        result.add(if (pending.size == 1) pending.single() else TransferTask(pending.first().path, bytes,
            pending.first().id, pending.first().sourceRoot, pending.map { it.path }))
        pending.clear(); bytes = 0
    }
    val tail = tasks.indexOfFirst { it.size?.let { size -> size < minBytes } == true }.let { if (it < 0) tasks.size else it }
    // Group the small tail by source root: a root change ends a job, so interleaved roots would defeat batching.
    val ordered = tasks.take(tail) + tasks.drop(tail).sortedWith(compareBy<TransferTask>({ it.sourceRoot }, { it.path }))
    for (task in ordered) {
        if (task.size == null || task.isBatch) {
            flush(); result.add(task); continue
        }
        if (pending.isNotEmpty() && pending.first().sourceRoot != task.sourceRoot) flush()
        pending.add(task); bytes += requireNotNull(task.size)
        if (bytes >= minBytes) flush()
    }
    flush()
    return result
}

internal fun batchSize(text: String): Long {
    val match = Regex("([0-9]+)(B|KB|MB|GB|KiB|MiB|GiB)?", RegexOption.IGNORE_CASE).matchEntire(text)
        ?: error("Invalid --batch-min-size: use bytes or a value such as 1MiB")
    val multiplier = when (match.groupValues[2].lowercase()) {
        "kb" -> 1000L; "mb" -> 1_000_000L; "gb" -> 1_000_000_000L
        "kib" -> 1024L; "mib" -> 1048576L; "gib" -> 1073741824L
        else -> 1L
    }
    val amount = match.groupValues[1].toLong()
    if (amount > Long.MAX_VALUE / multiplier) throw ArithmeticException("long overflow")
    val bytes = amount * multiplier
    require(bytes > 0) { "--batch-min-size must be positive" }
    return bytes
}
