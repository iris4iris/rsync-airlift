@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package iris.tools.airlift

import kotlinx.coroutines.*
import kotlinx.cinterop.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.*
import kotlin.test.*

class LinuxPlatformTest {
    @Test fun temporaryDirectoriesArePrivateAndDeletionDoesNotFollowLinks(): Unit = runBlocking {
        val owned = platformSshTemporaryDirectory("airlift-owned-")
        val outside = platformSshTemporaryDirectory("airlift-outside-")
        try {
            memScoped {
                val info = alloc<stat>()
                assertEquals(0, stat(owned.toString(), info.ptr))
                assertEquals(448u, info.st_mode and 511u) // 0700
            }
            assertEquals(0, symlink(outside.toString(), Path(owned, "link").toString()))
            platformDeleteSshDirectory(owned)
            assertTrue(SystemFileSystem.exists(outside))
            assertFalse(SystemFileSystem.exists(owned))
        } finally { platformDeleteSshDirectory(owned); platformDeleteSshDirectory(outside) }
    }

    @Test fun sshDirectoriesFallBackToTmpWhenTmpdirIsUnusable() {
        assertEquals("/tmp", sshTemporaryParent(null, "airlift-"))
        assertEquals("/tmp", sshTemporaryParent("", "airlift-"))
        assertEquals("/tmp", sshTemporaryParent("relative/tmp", "airlift-"))
        assertEquals("/var/tmp", sshTemporaryParent("/var/tmp/", "airlift-"))
        assertEquals("/", sshTemporaryParent("/", "airlift-"))
        // 69 + "/airlift-XXXXXX/s" (17) + ssh's bind suffix (17) = 103 bytes fits; 74 would exceed sun_path.
        assertEquals("/" + "d".repeat(68), sshTemporaryParent("/" + "d".repeat(68), "airlift-"))
        assertEquals("/tmp", sshTemporaryParent("/" + "d".repeat(73), "airlift-"))
        assertEquals("/tmp", sshTemporaryParent("/" + "д".repeat(40), "airlift-")) // 81 bytes in UTF-8
    }

    @Test fun lockSupportsReentryAndClockAndPathsAreUsable() {
        val lock = platformLock()
        assertEquals(42, lock.locked { lock.locked { 42 } })
        val clock = platformClock()
        val first = clock.monotonicNanos()
        assertTrue(clock.monotonicNanos() >= first)
        assertTrue(clock.wallTimeMillis() > 0)
        assertEquals(Path("/tmp/b"), platformPaths().absolute(Path("/tmp/a/../b")))
        assertNotNull(platformEnvironment()["PATH"])
    }
}
