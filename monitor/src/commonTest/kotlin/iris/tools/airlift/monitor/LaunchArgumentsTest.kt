package iris.tools.airlift.monitor

import iris.tools.airlift.AirliftConfig
import iris.tools.airlift.TransferDirection
import kotlin.test.*

class LaunchArgumentsTest {
    @Test fun `swap endpoints to reverse transfer and open monitor by default`() {
        val remote = "root@111.111.111.111:/var/lib/mysql/mydb/"
        val local = "/usr/local/mysql/data/mydb/"
        for ((paths, direction) in listOf(listOf(remote, local) to TransferDirection.DOWNLOAD, listOf(local, remote) to TransferDirection.UPLOAD)) {
            val args = LaunchArguments.parse(listOf("--files", "files.txt", "--workers=4") + paths)
            assertTrue("--run" in args.enabled)
            assertEquals("4", args.options["--workers"])
            val roots = TransferRoots.parse(args.paths)
            assertEquals(direction, roots.direction)
            assertEquals("root@111.111.111.111", roots.host)
            assertEquals("/var/lib/mysql/mydb/", roots.remoteRoot)
            assertEquals(local, roots.localRoot)
        }
    }

    @Test fun `support paths with spaces windows local paths and bracketed IPv6`() {
        val roots = TransferRoots.parse(listOf("user@[2001:db8::1]:/srv/my files/", "C:\\copy target\\"))
        assertEquals("user@2001:db8::1", roots.host)
        assertEquals("C:\\copy target\\", roots.localRoot)
        assertEquals(TransferDirection.DOWNLOAD, roots.direction)
    }

    @Test fun `headless serve and existing demo and attach modes remain available`() {
        assertEquals(setOf("--serve"), LaunchArguments.parse(listOf("--serve", "--files", "list", "/local", "user@host:/remote")).enabled)
        assertEquals(setOf("--demo"), LaunchArguments.parse(listOf("--demo")).enabled)
        assertTrue("--attach" in LaunchArguments.parse(listOf("--attach", "file", "--swing")).options)
    }

    @Test fun `ssh options attempts and numeric limits are validated with friendly errors`() {
        val args = LaunchArguments.parse(listOf("--ssh", "-o Compression=no", "--attempts", "3", "--workers=256", "--port", "2222",
            "user@host:/data/", "/backup/"))
        assertEquals("-o Compression=no", args.options["--ssh"])
        assertEquals(3, args.attempts)
        assertEquals(256, args.workers)
        assertEquals(2222, args.port)
        val failures = mapOf(listOf("--workers", "0") to "--workers must be a whole number from 1 to ${AirliftConfig.MAX_WORKERS}",
            listOf("--workers", "many") to "not 'many'", listOf("--workers", "257") to "1 to 256",
            listOf("--port", "70000") to "--port must be a whole number from 1 to 65535", listOf("--attempts", "11") to "1 to 10",
            listOf("--ssh", "-p 22") to "managed by Airlift", listOf("--rsync", "-av --rsh=ssh") to "Airlift manages")
        for ((options, message) in failures) {
            val error = assertFailsWith<IllegalArgumentException> { LaunchArguments.parse(options + listOf("user@host:/data/", "/backup/")) }
            assertContains(error.message.orEmpty(), message)
        }
    }

    @Test fun `reject ambiguous endpoints and obsolete direction flags before starting transfers`() {
        assertFailsWith<IllegalArgumentException> { TransferRoots.parse(listOf("host:/one", "other:/two")) }
        assertFailsWith<IllegalArgumentException> { TransferRoots.parse(listOf("/one", "/two")) }
        assertFailsWith<IllegalArgumentException> { LaunchArguments.parse(listOf("--upload", "--demo")) }
        assertFailsWith<IllegalArgumentException> { LaunchArguments.parse(listOf("--files", "list", "host:/one")) }
    }
}
