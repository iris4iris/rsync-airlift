package iris.tools.airlift.monitor

import iris.tools.airlift.*
import kotlin.test.*

class SkippedProgressTest {
    private fun snapshot(matched: Long, literal: Long) = AirliftSnapshot(
        listOf(TaskState(TransferTask("batch", 1000, files = listOf("a", "b")), TaskStatus.SUCCEEDED,
            progress = TransferProgress(1000, null, null, 100, matched, literal))),
        emptyList(), false, false, 1, 1000, 11000)

    @Test fun `skipped matched and new occupy separate nonoverlapping colors`() {
        val breakdown = OverallBreakdown.from(snapshot(200, 100))
        assertEquals(700, breakdown.skippedBytes)
        assertEquals(200, breakdown.matchedBytes)
        assertEquals(100, breakdown.literalBytes)
        val bar = breakdown.segments(100)
        fun width(color: MonitorColor) = bar.filter { it.color == color }.sumOf { it.text.length }
        assertEquals(70, width(MonitorColor.ANSI.BLUE))
        assertEquals(20, width(MonitorColor.ANSI.CYAN))
        assertEquals(10, width(MonitorColor.ANSI.GREEN))
        assertEquals(100, bar.sumOf { it.text.length })
        assertContains(breakdown.summary(1000), "Skipped 700.0B (70.0%)")
    }

    @Test fun `final average excludes skipped but retains delta matched bytes and full elapsed time`() {
        for ((matched, literal, expected) in listOf(Triple(200L, 100L, "30.0B/s"), Triple(0L, 0L, "0.0B/s"), Triple(900L, 100L, "100.0B/s"))) {
            val snapshot = snapshot(matched, literal)
            val source = object : MonitorSource {
                override suspend fun snapshot() = snapshot
                override suspend fun pause() {}
                override suspend fun resume() {}
                override suspend fun setWorkerCount(count: Int) {}
            }
            val frame = MonitorRenderer(source).render(MonitorSize(140, 28), snapshot, MonitorModel())
            val summary = frame.runs.single { it.text.startsWith("Processed ") }.text
            assertContains(summary, expected)
            assertFalse("ETA" in summary)
        }
    }
    @Test fun `overall ETA is never shorter than a running worker estimate`() {
        for ((workerEta, expected) in listOf(180L to "00:03:00", 1L to "00:00:01")) {
            val task = TaskState(TransferTask("active", 1000), TaskStatus.RUNNING,
                progress = TransferProgress(900, 100.0, workerEta, 90), progressUpdatedAtNanos = monitorMonotonicNanos())
            val snapshot = AirliftSnapshot(listOf(task), emptyList(), false, false, 1, 1000)
            val source = object : MonitorSource {
                override suspend fun snapshot() = snapshot
                override suspend fun pause() {}
                override suspend fun resume() {}
                override suspend fun setWorkerCount(count: Int) {}
            }
            val frame = MonitorRenderer(source).render(MonitorSize(140, 28), snapshot, MonitorModel())
            assertContains(frame.runs.single { it.text.startsWith("Processed ") }.text, "ETA $expected")
        }
    }

}
