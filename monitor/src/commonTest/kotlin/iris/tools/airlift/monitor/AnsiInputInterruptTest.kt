package iris.tools.airlift.monitor

import kotlin.test.*

class AnsiInputInterruptTest {
    @Test fun `ctrl C inside a bracketed paste is not an interrupt`() {
        val input = AnsiInput()
        input.feed("\u001b[200~rm -rf\u0003\u0004 q\u001b[201~".encodeToByteArray())
        assertNull(input.poll())
        assertFalse(input.takeInterrupt())
    }

    @Test fun `typed ctrl C interrupts once and ctrl D only detaches`() {
        val input = AnsiInput()
        input.feed("\u0004".encodeToByteArray())
        assertEquals(MonitorEvent.Key(MonitorKey.EOF), input.poll())
        assertFalse(input.takeInterrupt())
        input.feed("\u0003".encodeToByteArray())
        assertEquals(MonitorEvent.Key(MonitorKey.EOF), input.poll())
        assertTrue(input.takeInterrupt())
        assertFalse(input.takeInterrupt())
    }

    @Test fun `ctrl C after a completed paste interrupts`() {
        val input = AnsiInput()
        input.feed("\u001b[200~\u0003\u001b[201~\u0003".encodeToByteArray())
        assertEquals(MonitorEvent.Key(MonitorKey.EOF), input.poll())
        assertTrue(input.takeInterrupt())
    }
}
