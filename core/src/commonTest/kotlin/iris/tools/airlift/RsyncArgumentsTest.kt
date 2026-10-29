package iris.tools.airlift

import kotlinx.io.files.Path
import kotlin.test.*

class RsyncArgumentsTest {
    @Test fun `custom copy options replace partial-dir defaults without breaking monitoring`() {
        val config = AirliftConfig("user@host", "/source", Path("dest"),
            rsyncOptions = "-avH --partial --inplace --bwlimit=90000")
        val command = RsyncCommand.build(config, "ssh -S socket", "user@host:/source/", "/dest/")
        assertEquals(listOf("rsync", "-avH", "--partial", "--inplace", "--bwlimit=90000"), command.take(5))
        assertFalse(command.any { it.startsWith("--partial-dir=") })
        assertContains(command, "--files-from=-")
        assertContains(command, "--info=progress2")
        assertContains(command, "--stats")
        assertEquals(listOf("--", "user@host:/source/", "/dest/"), command.takeLast(3))
    }

    @Test fun `tokenizer preserves quoted values and does not evaluate shell expressions`() {
        assertEquals(listOf("--exclude", "cache dir/*", "--suffix=.backup copy", "--filter", "- *.tmp"),
            RsyncArguments.parse("--exclude 'cache dir/*' --suffix=\".backup copy\" --filter \"- *.tmp\""))
        assertEquals(listOf("--suffix=\$(touch marker)"), RsyncArguments.parse("--suffix='\$(touch marker)'"))
        assertEquals(listOf("--suffix=a b"), RsyncArguments.parse("--suffix=a\\ b"))
    }

    @Test fun `malformed quoting and owned transport options fail before starting a process`() {
        for (text in listOf("--suffix='bad", "-a\\", "--files-from=other", "-e 'other ssh'", "--rsh=other", "--")) {
            assertFailsWith<IllegalArgumentException> { RsyncArguments.parse(text) }
        }
    }

    @Test fun `human-readable output is switched off after user options`() {
        val command = RsyncCommand.build(AirliftConfig("user@host", "/source", Path("dest"), rsyncOptions = "-avh"), "ssh", "s", "d")
        assertTrue(command.indexOf("--no-human-readable") > command.indexOf("-avh"))
    }

    @Test fun `transport inside short option clusters and stdin consumers are reserved`() {
        for (text in listOf("-ave ssh", "-avessh", "--exclude-from=-", "--include-from -", "--filter='merge -'",
            "--filter=. -", "-f'. -'", "-avf 'dir-merge -'", "-av extra-source")) {
            assertFailsWith<IllegalArgumentException>(text) { RsyncArguments.parse(text) }
        }
    }

    @Test fun `option values are not mistaken for reserved options`() {
        assertEquals(listOf("-B", "-e", "--exclude", "-e*", "--suffix", "-e", "-av"),
            RsyncArguments.parse("-B -e --exclude -e* --suffix -e -av"))
        assertEquals(listOf("--exclude-from=list.txt", "-f", "- *.tmp", "--del"),
            RsyncArguments.parse("--exclude-from=list.txt -f '- *.tmp' --del"))
    }

    @Test fun `ssh options accept tuning and reject options that break worker connections`() {
        assertEquals(listOf("-c", "aes128-gcm@openssh.com", "-o", "Compression=no", "-4", "-J", "jump@bastion"),
            SshOptions.parse("-c aes128-gcm@openssh.com -o Compression=no -4 -J jump@bastion"))
        for (text in listOf("-M", "-S /tmp/s", "-O exit", "-N", "-f", "-p 2222", "-i key", "-o ControlPath=/x",
            "-o 'ControlMaster no'", "-L 80:host:80", "-G", "host", "--long", "-o")) {
            assertFailsWith<IllegalArgumentException>(text) { SshOptions.parse(text) }
        }
        assertEquals(listOf("-o", "Compression=no"), AirliftConfig("user@host", "/source", Path("dest"), sshOptions = "-o Compression=no").sshArguments)
    }

    @Test fun `worker pool size is bounded`() {
        assertFailsWith<IllegalArgumentException> { AirliftConfig("user@host", "/source", Path("dest"), workers = AirliftConfig.MAX_WORKERS + 1) }
        AirliftConfig("user@host", "/source", Path("dest"), workers = AirliftConfig.MAX_WORKERS)
    }

    @Test fun `no copy policy is injected when options are omitted or empty`() {
        for (options in listOf(null, "")) {
            val command = RsyncCommand.build(AirliftConfig("user@host", "/source", Path("dest"), rsyncOptions = options), "ssh", "source", "dest")
            assertEquals(listOf("rsync", "--relative", "--protect-args", "--from0", "--files-from=-", "--info=progress2", "--outbuf=L", "--stats", "--no-human-readable", "--info=name1", "--out-format=AIRLIFT_FILE:%n", "-e", "ssh", "--", "source", "dest"), command)
        }
    }
}
