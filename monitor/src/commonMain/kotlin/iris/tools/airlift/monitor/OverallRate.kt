package iris.tools.airlift.monitor

import iris.tools.airlift.*

/** Bridge short file handovers, without keeping an old speed through a real stall. */
internal class OverallRate {
    private var lastComplete: Double? = null
    private var lastCompleteAt = 0L
    private var run: Long? = null

    fun value(snapshot: AirliftSnapshot, now: Long = monitorMonotonicNanos()): Double? =
        value(snapshot, snapshot.tasks.filter { it.status == TaskStatus.RUNNING }, now)

    /** [active] are the snapshot's RUNNING tasks, already collected by the caller's aggregate pass. */
    fun value(snapshot: AirliftSnapshot, active: List<TaskState>, now: Long = monitorMonotonicNanos()): Double? {
        if (run != snapshot.startedAtMillis) { lastComplete = null; run = snapshot.startedAtMillis }
        if (active.isEmpty() || snapshot.cancelled || snapshot.finishedAtMillis != null) {
            lastComplete = null
            return null
        }
        val rates = active.mapNotNull { MonitorModel.freshRate(it, now) }
        if (rates.size == active.size) {
            lastComplete = rates.sum()
            lastCompleteAt = now
            return lastComplete
        }
        if (lastComplete != null && now - lastCompleteAt < 3_000_000_000L) return lastComplete
        return rates.takeIf { it.isNotEmpty() }?.sum()
    }
}
