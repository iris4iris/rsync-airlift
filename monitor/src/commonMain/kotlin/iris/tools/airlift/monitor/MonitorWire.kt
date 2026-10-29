package iris.tools.airlift.monitor

import iris.tools.airlift.*

/** Protocol 4 snapshot codec. Batches carry only their file total, never the file list. */
internal object Wire {
    const val MAX_TEXT = 1_048_576
    /** Sanity bound for tasks/workers per snapshot, far above any realistic run. */
    const val MAX_COUNT = 50_000_000

    suspend fun text(output: MonitorOutput, text: String) {
        val bytes = text.encodeToByteArray()
        require(bytes.size <= MAX_TEXT) { "Monitor text field is too long" }
        output.writeInt(bytes.size); output.write(bytes)
    }
    suspend fun text(input: MonitorInput): String {
        val size = input.readInt()
        require(size in 0..MAX_TEXT) { "Invalid monitor text length" }
        return ByteArray(size).also { input.readFully(it) }.decodeToString()
    }
    private suspend fun nullable(output: MonitorOutput, value: String?) { output.writeBoolean(value != null); value?.let { text(output, it) } }
    private suspend fun nullable(input: MonitorInput) = if (input.readBoolean()) text(input) else null
    private fun number(output: MonitorOutput, value: Long?) { output.writeBoolean(value != null); value?.let { output.writeLong(it) } }
    private suspend fun number(input: MonitorInput) = if (input.readBoolean()) input.readLong() else null
    private fun integer(output: MonitorOutput, value: Int?) { output.writeBoolean(value != null); value?.let { output.writeInt(it) } }
    private suspend fun integer(input: MonitorInput) = if (input.readBoolean()) input.readInt() else null
    private suspend fun count(input: MonitorInput): Int = input.readInt().also { require(it in 0..MAX_COUNT) { "Invalid monitor list size" } }

    suspend fun write(output: MonitorOutput, s: AirliftSnapshot) {
        output.writeBoolean(s.paused); output.writeBoolean(s.cancelled); output.writeInt(s.targetWorkers)
        number(output, s.startedAtMillis); number(output, s.finishedAtMillis)
        output.writeInt(s.tasks.size)
        val now = monitorMonotonicNanos()
        for (t in s.tasks) {
            text(output, t.task.path); number(output, t.task.size)
            nullable(output, t.task.id.takeIf { it != t.task.path })
            output.writeInt(t.task.fileTotal)
            text(output, t.status.name); output.writeInt(t.attempts)
            nullable(output, t.error); nullable(output, t.warning); nullable(output, t.logFile?.toString())
            // nanoTime origins differ between processes; transmit age instead.
            number(output, t.progressUpdatedAtNanos?.let { (now - it).coerceAtLeast(0) })
            output.writeBoolean(t.progress != null)
            t.progress?.let {
                output.writeLong(it.logicalBytes)
                output.writeBoolean(it.bytesPerSecond != null)
                it.bytesPerSecond?.let { value -> output.writeDouble(value) }
                number(output, it.remainingSeconds)
                integer(output, it.percent)
                number(output, it.matchedBytes)
                number(output, it.literalBytes)
                nullable(output, it.currentFile)
                integer(output, it.transferredFiles)
                integer(output, it.currentFileIndex)
            }
            output.drain()
        }
        output.writeInt(s.workers.size)
        for (w in s.workers) {
            output.writeInt(w.id); text(output, w.status.name); nullable(output, w.taskId); nullable(output, w.error)
        }
    }

    suspend fun read(input: MonitorInput): AirliftSnapshot {
        val paused = input.readBoolean(); val cancelled = input.readBoolean(); val target = input.readInt()
        val started = number(input); val finished = number(input)
        val taskCount = count(input)
        val tasks = ArrayList<TaskState>(minOf(taskCount, 65536))
        repeat(taskCount) {
            val path = text(input); val size = number(input); val id = nullable(input) ?: path
            val fileTotal = input.readInt().also { require(it in 0..MAX_COUNT) { "Invalid monitor file total" } }
            val task = TransferTask(path, size, id, fileTotal = fileTotal)
            val status = TaskStatus.valueOf(text(input)); val attempts = input.readInt()
            val error = nullable(input); val warning = nullable(input)
            val log = nullable(input)?.let { kotlinx.io.files.Path(it) }
            val updated = number(input)?.let { monitorMonotonicNanos() - it }
            val progress = if (input.readBoolean()) {
                val logical = input.readLong()
                val rate = if (input.readBoolean()) input.readDouble() else null
                TransferProgress(logical, rate, remainingSeconds = number(input), percent = integer(input),
                    matchedBytes = number(input), literalBytes = number(input), currentFile = nullable(input),
                    transferredFiles = integer(input), currentFileIndex = integer(input))
            } else null
            tasks += TaskState(task, status, attempts, progress, error, log, updated, warning)
        }
        val workerCount = count(input)
        val workers = ArrayList<WorkerState>(minOf(workerCount, 4096))
        repeat(workerCount) {
            workers += WorkerState(input.readInt(), WorkerStatus.valueOf(text(input)), nullable(input), nullable(input))
        }
        return AirliftSnapshot(tasks, workers, paused, cancelled, target, started, finished)
    }
}
