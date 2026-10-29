package iris.tools.airlift.monitor

import iris.tools.airlift.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class MultipleSourcesTest {
    private suspend fun plan(paths: List<String>, manifest: Path? = null): PreparedTransfer {
        val roots = TransferRoots.parseSources(paths)
        return prepareSources(AirliftConfig(roots.first().host, roots.first().remoteRoot,
            Path.of(sourceBase(roots.first().localRoot.replace('\\', '/'))).toPortablePath(), direction = roots.first().direction), roots, manifest?.toPortablePath())
    }

    @Test fun `expanded local masks accept multiple files sorted by size with individual source roots`() = kotlinx.coroutines.test.runTest {
        val root = Files.createTempDirectory("airlift-multi-")
        try {
            val a = Files.createDirectories(root.resolve("one")).resolve("a.MYD")
            val b = Files.createDirectories(root.resolve("two")).resolve("b.MYD")
            Files.write(a, ByteArray(2)); Files.write(b, ByteArray(20))
            val paths = listOf(a.toString(), b.toString(), "user@host:/dest/")
            assertTrue("--run" in LaunchArguments.parse(paths).enabled)
            val tasks = plan(paths).tasks
            assertEquals(listOf("b.MYD", "a.MYD"), tasks.map { it.path })
            assertEquals(listOf(20L, 2L), tasks.map { it.size })
            assertEquals(b.parent.toString().replace('\\', '/'), tasks.first().sourceRoot)
            assertEquals(2, plan(listOf(a.toString(), a.toString(), b.toString(), paths.last())).tasks.size)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `directory trailing slash and conflicting destinations are handled`() = kotlinx.coroutines.test.runTest {
        val root = Files.createTempDirectory("airlift-multi-")
        try {
            val a = Files.createDirectories(root.resolve("one"))
            val b = Files.createDirectories(root.resolve("two"))
            Files.write(a.resolve("same"), ByteArray(2)); Files.write(b.resolve("same"), ByteArray(3))
            assertEquals(setOf("one/same", "two/same"), plan(listOf(a.toString(), b.toString(), "user@host:/dest/")).tasks.map { it.path }.toSet())
            assertFailsWith<IllegalArgumentException> { plan(listOf("$a/", "$b/", "user@host:/dest/")) }
            val manifest = root.resolve("list")
            Files.writeString(manifest, "same\n")
            assertEquals(2, plan(listOf(a.toString(), b.toString(), "user@host:/dest/"), manifest).tasks.size)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `several files from one directory give a directory upload root and literal bracket names`() = kotlinx.coroutines.test.runTest {
        val root = Files.createTempDirectory("airlift-multi-")
        try {
            val a = root.resolve("a.bin")
            val b = root.resolve("b[1].bin")
            Files.write(a, ByteArray(2)); Files.write(b, ByteArray(3)); Files.write(root.resolve("b1.bin"), ByteArray(9))
            val plan = plan(listOf(a.toString(), b.toString(), "user@host:/dst/"))
            assertEquals(root.toPortablePath(), plan.config.localRoot)
            assertTrue(Files.isDirectory(Path.of(plan.config.localRoot.toString())))
            assertEquals(listOf("b[1].bin" to 3L, "a.bin" to 2L), plan.tasks.map { it.path to it.size })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `dot sources copy contents and masks without matches fail`() = kotlinx.coroutines.test.runTest {
        val root = Files.createTempDirectory("airlift-multi-")
        try {
            val a = Files.createDirectories(root.resolve("one"))
            val b = Files.createDirectories(root.resolve("two"))
            Files.write(a.resolve("x"), ByteArray(2)); Files.write(b.resolve("y"), ByteArray(3))
            val plan = plan(listOf("$a/.", "$b/sub/..", b.toString(), "user@host:/dest/"))
            assertEquals(setOf("x", "y", "two/y"), plan.tasks.map { it.path }.toSet())
            assertEquals(a.toString().replace('\\', '/'), plan.tasks.single { it.path == "x" }.sourceRoot)
            val failure = assertFailsWith<IllegalArgumentException> { plan(listOf("$a/*.none", b.toString(), "user@host:/dest/")) }
            assertContains(failure.message.orEmpty(), "matched no files")
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `remote sources require one host and one direction`() = kotlinx.coroutines.test.runTest {
        assertEquals(2, TransferRoots.parseSources(listOf("user@host:/one", "user@host:/two", "/dest")).size)
        assertFailsWith<IllegalArgumentException> { TransferRoots.parseSources(listOf("user@host:/one", "user@other:/two", "/dest")) }
        assertFailsWith<IllegalArgumentException> { TransferRoots.parseSources(listOf("/local", "user@host:/two", "/dest")) }
    }
}
