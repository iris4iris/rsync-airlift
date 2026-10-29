package iris.tools.airlift.monitor

import kotlin.test.*

class BreakdownPercentTest {
    @Test fun `percentages use processed bytes including progress awaiting statistics`() {
        val breakdown = OverallBreakdown(0.0, 0.0, 0.0, 0.0, 60, 20)
        assertEquals("Skipped 0.0B (0.0%) | Matched 60.0B (60.0%) | New 20.0B (20.0%)", breakdown.summary(100))
        assertEquals("Skipped 0.0B (0.0%) | Matched 60.0B (75.0%) | New 20.0B (25.0%)", breakdown.summary(80))
    }

    @Test fun `empty run has zero percentages`() {
        assertEquals("Skipped 0.0B (0.0%) | Matched 0.0B (0.0%) | New 0.0B (0.0%)", OverallBreakdown(0.0, 0.0, 0.0, 0.0, 0, 0).summary(0))
    }
}
