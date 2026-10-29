package iris.tools.airlift

import kotlin.test.*

class RsyncDiagnosticsTest {
    @Test fun `escaped non-ASCII file names are shown readably`() {
        val diagnostics = RsyncDiagnostics()
        diagnostics.accept("rsync: [sender] link_stat \"/src/\\#321\\#204\\#320\\#260\\#320\\#271\\#320\\#273.bin\" failed: No such file or directory (2)")
        assertEquals("rsync: [sender] link_stat \"/src/файл.bin\" failed: No such file or directory (2)", diagnostics.summary())
    }

    @Test fun `remote missing command precedes generic rsync summary`() {
        val diagnostics = RsyncDiagnostics()
        diagnostics.accept("bash: rsync: command not found")
        diagnostics.accept("rsync: connection unexpectedly closed (0 bytes received so far) [Receiver]")
        diagnostics.accept("rsync error: remote command not found (code 127) at io.c(232)")
        assertEquals("bash: rsync: command not found", diagnostics.summary())
    }

    @Test fun `file errors survive progress statistics and terminal escapes`() {
        val diagnostics = RsyncDiagnostics()
        diagnostics.accept("AIRLIFT_FILE:data.bin")
        diagnostics.accept(" 1,024 50% 1.00MB/s 0:00:01")
        diagnostics.accept("\u001B[31mrsync: [receiver] open file failed: Permission denied (13)\u001B[0m")
        repeat(100) { diagnostics.accept("Total file size: 2,048 bytes") }
        diagnostics.accept("rsync error: some files were not transferred (code 23)")
        assertEquals("rsync: [receiver] open file failed: Permission denied (13)", diagnostics.summary())
    }

    @Test fun `unknown errors remain visible but ordinary telemetry is ignored`() {
        val diagnostics = RsyncDiagnostics()
        diagnostics.accept("AIRLIFT_FILE:hello")
        diagnostics.accept("100 100% 1.00MB/s 0:00:00")
        diagnostics.accept("Matched data: 100 bytes")
        assertNull(diagnostics.summary())
        diagnostics.accept("unexpected remote failure")
        assertEquals("unexpected remote failure", diagnostics.summary())
    }

    @Test fun `diagnostics are bounded and single line`() {
        val diagnostics = RsyncDiagnostics()
        repeat(100) { diagnostics.accept("message $it") }
        diagnostics.accept("ssh: " + "x".repeat(10_000) + "\u0007")
        assertEquals(600, diagnostics.summary()!!.length)
        assertFalse(diagnostics.summary()!!.contains('\u0007'))
    }
}
