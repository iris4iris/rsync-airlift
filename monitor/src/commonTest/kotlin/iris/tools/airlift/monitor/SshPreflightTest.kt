package iris.tools.airlift.monitor

import iris.tools.airlift.AirliftConfig
import kotlin.test.*

class SshPreflightTest {
    private val config = AirliftConfig("user@host", "/source", kotlinx.io.files.Path("dest"), port = 2222)
    private val unknown = SshCheckResult(255, "No ECDSA host key is known for host and you have requested strict checking.\nHost key verification failed.")

    @Test fun `trusted host needs no prompt`() = kotlinx.coroutines.test.runTest {
        var calls = 0
        checkSshConnection(config, false, { it }) { command, prompt ->
            calls++; assertFalse(prompt); assertContains(command, "StrictHostKeyChecking=yes")
            SshCheckResult(0, "")
        }
        assertEquals(1, calls)
    }

    @Test fun `extra SSH options follow Airlift options in every preflight command`() = kotlinx.coroutines.test.runTest {
        val custom = config.copy(sshOptions = "-o Compression=no")
        var calls = 0
        checkSshConnection(custom, true, { it }) { command, _ ->
            val host = command.indexOf("user@host")
            assertEquals(listOf("-o", "Compression=no"), command.subList(host - 2, host))
            assertEquals("true", command.last())
            if (calls++ == 0) unknown else SshCheckResult(0, "")
        }
        assertEquals(3, calls)
    }

    @Test fun `unknown key prompts once through OpenSSH and strictly retries with password wrapper`() = kotlinx.coroutines.test.runTest {
        var calls = 0
        checkSshConnection(config, true, { listOf("sshpass", "-e") + it }) { command, prompt ->
            when (calls++) {
                0 -> { assertEquals("sshpass", command.first()); unknown }
                1 -> {
                    assertTrue(prompt); assertEquals("ssh", command.first())
                    assertContains(command, "StrictHostKeyChecking=ask")
                    assertContains(command, "PreferredAuthentications=none")
                    assertContains(command, "2222")
                    SshCheckResult(255, "Permission denied")
                }
                else -> { assertFalse(prompt); assertContains(command, "StrictHostKeyChecking=yes"); SshCheckResult(0, "") }
            }
        }
        assertEquals(3, calls)
    }

    @Test fun `declined key never starts transfers`() = kotlinx.coroutines.test.runTest {
        var calls = 0
        assertFailsWith<IllegalStateException> {
            checkSshConnection(config, true, { it }) { _, prompt -> calls++; if (prompt) SshCheckResult(255, "Host key verification failed") else unknown }
        }
        assertEquals(3, calls)
    }

    @Test fun `changed host key and auth failures never offer trust prompt`() = kotlinx.coroutines.test.runTest {
        for (error in listOf("REMOTE HOST IDENTIFICATION HAS CHANGED!", "Permission denied (publickey,password).")) {
            var calls = 0
            var passwordPrompts = 0
            assertFailsWith<IllegalStateException> {
                checkSshConnection(config, true, authenticate = { it }, passwordAvailable = { false },
                    readPassword = { passwordPrompts++; null },
                    installPassword = { fail("Cancelled password must not be installed") },
                ) { _, prompt -> calls++; assertFalse(prompt); SshCheckResult(255, error) }
            }
            assertEquals(1, calls)
            assertEquals(if (error.startsWith("Permission denied")) 1 else 0, passwordPrompts)
        }
        assertFalse(unknownHostKey("REMOTE HOST IDENTIFICATION HAS CHANGED!\n${unknown.output}"))
    }

    @Test fun `headless unknown key gives actionable error without prompting`() = kotlinx.coroutines.test.runTest {
        var calls = 0
        val failure = assertFailsWith<IllegalStateException> {
            checkSshConnection(config, false, { it }) { _, prompt -> calls++; assertFalse(prompt); unknown }
        }
        assertContains(failure.message.orEmpty(), "interactive terminal")
        assertEquals(1, calls)
    }
}
