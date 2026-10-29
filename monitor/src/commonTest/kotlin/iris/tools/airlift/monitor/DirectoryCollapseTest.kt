package iris.tools.airlift.monitor

import iris.tools.airlift.TransferTask
import kotlin.test.*

class DirectoryCollapseTest {
    private fun inventory(vararg files: String) = DirectoryInventory(null, "", files.toSet(), emptySet(), emptySet())
    private fun job(vararg files: String) = TransferTask(files.first(), files.size.toLong(), files = files.toList())

    @Test fun `seventeen thousand files collapse to root without losing accounting`() {
        val files = (1..17000).map { "f$it" }
        val input = files.map { TransferTask(it, 0) }
        val jobs = batchSmallFiles(input)
        val optimized = DirectoryCollapser(listOf(inventory(*files.toTypedArray()))).optimize(jobs.single())
        assertEquals(listOf("."), optimized.entries)
        assertEquals(17000, optimized.fileCount)
        assertEquals(0L, optimized.size)
        assertEquals(files.toSet(), optimized.files.toSet())
    }

    @Test fun `hybrid list collapses only fully owned directories and never already assigned files`() {
        val collapser = DirectoryCollapser(listOf(inventory("a/x", "a/y", "b/x", "b/y")))
        assertEquals(listOf("a/", "b/x"), collapser.optimize(job("a/x", "a/y", "b/x")).entries)
        assertTrue(collapser.optimize(job("a/x", "b/x")).entries.isEmpty())
        assertEquals(listOf("."), collapser.optimize(job("a/x", "a/y", "b/x", "b/y")).entries)
    }

    @Test fun `empty dirs special entries and incomplete inventory prevent parent collapse`() {
        val source = inventory("a/x", "a/y").copy(directories = setOf("a", "empty"))
        assertEquals(listOf("a/"), DirectoryCollapser(listOf(source)).optimize(job("a/x", "a/y")).entries)
        val special = inventory("a/x", "a/y").copy(otherEntries = setOf("a/socket"))
        assertTrue(DirectoryCollapser(listOf(special)).optimize(job("a/x", "a/y")).entries.isEmpty())
        assertTrue(DirectoryCollapser(emptyList()).optimize(job("a/x", "a/y")).entries.isEmpty())
        assertTrue(DirectoryCollapser(listOf(inventory("a/u", "a/v"))).optimize(job("a/x", "a/y")).entries.isEmpty())
    }

    @Test fun `scoped inventories cannot prove their parents or another source root`() {
        val source = DirectoryInventory("/source", "a", setOf("a/x", "a/y"), setOf("a"), emptySet())
        assertEquals(listOf("a/"), DirectoryCollapser(listOf(source)).optimize(job("a/x", "a/y").copy(sourceRoot = "/source")).entries)
        assertTrue(DirectoryCollapser(listOf(source)).optimize(job("a/x", "a/y")).entries.isEmpty())
    }

    @Test fun `destructive or symlink following options keep exact file lists`() {
        for (options in listOf("--delete", "--delete-excluded", "--remove-source-files", "-aL", "--copy-dirlinks", "--no-recursive")) {
            assertTrue(DirectoryCollapser(listOf(inventory("a", "b")), options).optimize(job("a", "b")).entries.isEmpty())
        }
    }

    @Test fun `aliases of destructive options and filter options disable collapse while option values are not scanned`() {
        val disabling = listOf("--del", "--remove-sent-files", "--copy-unsafe-links", "--exclude=*.tmp", "--exclude '*.tmp'",
            "--include=x", "--filter='- *.o'", "-f '- *.o'", "-avF", "-C", "--cvs-exclude", "-x", "--one-file-system",
            "--exclude-from=list", "--include-from list", "--delete-after", "-aK", "--no-r")
        for (options in disabling) assertFalse(CollapsePolicy.allows(options), options)
        val allowed = listOf("-av --partial", "--delay-updates", "-T /tmp/Lkx", "--temp-dir /tmp/CFx", "--suffix=-L",
            "--partial-dir=.Kx", "-B4096", "--existing", "--ignore-existing", "--max-size=1G", "--info=progress2", "-a --chmod=F644")
        for (options in allowed) assertTrue(CollapsePolicy.allows(options), options)
        val collapser = DirectoryCollapser(listOf(inventory("a/x", "a/y")), "-a --del")
        assertTrue(collapser.optimize(job("a/x", "a/y")).entries.isEmpty())
    }

    @Test fun `inventories of one source root are merged and indexed by scope`() {
        // 2,000 named source directories under one parent share a source root; optimize must not scan them all per job.
        val inventories = (1..2000).map { DirectoryInventory("/data", "d$it", setOf("d$it/x", "d$it/y"), setOf("d$it"), emptySet()) }
        val collapser = DirectoryCollapser(inventories)
        val jobs = (1..2000).map { job("d$it/x", "d$it/y").copy(sourceRoot = "/data") }
        assertTrue(jobs.all { collapser.optimize(it).entries == listOf("${it.path.substringBefore('/')}/") })
        // The parent of the named directories is not provable from their scopes.
        assertTrue(collapser.optimize(job("d1/x", "d1/y", "d2/x", "d2/y").copy(sourceRoot = "/data")).entries.let { it == listOf("d1/", "d2/") })
    }

    @Test fun `a depth limited listing keeps blocking directories another inventory lists only partly`() {
        val limited = DirectoryInventory("/src", "", setOf("top"), setOf("", "one"), emptySet())
        val nested = DirectoryInventory("/src", "one/sub", setOf("one/sub/a", "one/sub/b"), setOf("one/sub"), emptySet())
        val collapser = DirectoryCollapser(listOf(limited, nested))
        val entries = collapser.optimize(job("one/sub/a", "one/sub/b").copy(sourceRoot = "/src")).entries
        assertEquals(listOf("one/sub/"), entries)
    }

    @Test fun `large prefix stays size ordered while small tail becomes path ordered`() {
        val tasks = listOf(TransferTask("big-b", 300), TransferTask("big-a", 200), TransferTask("z", 90), TransferTask("a", 80), TransferTask("b", 10))
        val jobs = batchSmallFiles(tasks, 100)
        assertEquals(listOf("big-b", "big-a"), jobs.take(2).map { it.path })
        assertEquals(listOf("a", "b", "z"), jobs.last().files)
    }
}
