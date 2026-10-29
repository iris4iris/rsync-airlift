package iris.tools.airlift.monitor

import iris.tools.airlift.*
import kotlin.test.*

class BatchPlanningTest {
    @Test fun `batch size rejects overflow and retains the largest valid values`() {
        assertEquals(Long.MAX_VALUE, batchSize(Long.MAX_VALUE.toString()))
        val largestMiB = Long.MAX_VALUE / 1048576
        assertEquals(largestMiB * 1048576, batchSize("${largestMiB}MiB"))
        assertFailsWith<ArithmeticException> { batchSize("${largestMiB + 1}MiB") }
        assertFailsWith<ArithmeticException> { batchSize("${Long.MAX_VALUE}GB") }
        assertFailsWith<IllegalArgumentException> { batchSize("0MiB") }
    }

    @Test fun `default batches target five MiB and decimal units differ from binary`() {
        val files = (1..6).map { TransferTask("f$it", 1048576) }
        assertEquals(listOf(5, 1), batchSmallFiles(files).map { it.fileCount })
        assertEquals(5_000_000L, batchSize("5MB"))
        assertEquals(5_242_880L, batchSize("5MiB"))
        assertEquals(5_000_000L, batchSize("5mb"))
        assertEquals(1000L, batchSize("1KB"))
        assertEquals(1_000_000_000L, batchSize("1GB"))
    }
    @Test fun `fill until minimum or queue end retaining large files and order`() {
        val files = listOf(TransferTask("big", 3000), TransferTask("a", 600), TransferTask("b", 600), TransferTask("c", 100), TransferTask("zero", 0))
        val jobs = batchSmallFiles(files, 1024)
        assertEquals(listOf(3000L, 1200L, 100L), jobs.map { it.size })
        assertEquals(listOf(1, 2, 2), jobs.map { it.fileCount })
        assertEquals(listOf("a", "b"), jobs[1].files)
        assertEquals(5, jobs.sumOf { it.fileCount })
        assertEquals(files.sumOf { it.size!! }, jobs.sumOf { it.size!! })
    }

    @Test fun `empty files share one rsync and roots never mix`() {
        assertEquals(1000, batchSmallFiles((1..1000).map { TransferTask("f$it", 0) }).single().fileCount)
        val jobs = batchSmallFiles(listOf(TransferTask("a", 1, sourceRoot = "/a"), TransferTask("b", 1, sourceRoot = "/b")))
        assertEquals(2, jobs.size)
        assertEquals(1048576, batchSize("1MiB"))
        assertFails { batchSize("0") }
    }

    @Test fun `small files from interleaved source roots are grouped per root`() {
        val tasks = (1..100).map { TransferTask("Movies/f" + it.toString().padStart(3, '0'), 1, sourceRoot = if (it % 2 == 0) "/mnt/disk2" else "/mnt/disk1") }
        val jobs = batchSmallFiles(tasks, 1000)
        assertEquals(listOf("/mnt/disk1", "/mnt/disk2"), jobs.map { it.sourceRoot })
        assertEquals(listOf(50, 50), jobs.map { it.fileCount })
        assertEquals(jobs.first().files.sorted(), jobs.first().files)
        val large = batchSmallFiles(listOf(TransferTask("big", 5000, sourceRoot = "/b"), TransferTask("a", 1, sourceRoot = "/a")), 1000)
        assertEquals(listOf("big", "a"), large.map { it.path })
    }

    @Test fun `minimum is a total load threshold rather than an individual file cutoff`() {
        val jobs = batchSmallFiles(listOf(TransferTask("a", 600), TransferTask("b", 2500)), 1024)
        assertEquals(1, jobs.size)
        assertEquals(3100L, jobs.single().size)
    }

    @Test fun `batch progress retains announced file and transfer count through final statistics`() {
        val parser = RsyncOutputParser(setOf("a", "b"))
        assertEquals("a", parser.parse("AIRLIFT_FILE:a")?.currentFile)
        val progress = parser.parse("  100  50%  1.00kB/s  0:00:01 (xfr#1, to-chk=1/2)")!!
        assertEquals(1, progress.transferredFiles)
        assertEquals("a", progress.currentFile)
        parser.parse("AIRLIFT_FILE:b")
        parser.parse("Literal data: 10 bytes")
        val final = parser.parse("Matched data: 90 bytes")!!
        assertEquals("b", final.currentFile)
        assertEquals(90, final.matchedBytes)
    }
}
