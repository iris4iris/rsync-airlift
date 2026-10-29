package iris.tools.airlift.monitor

import iris.tools.airlift.*
import kotlinx.io.files.Path
import kotlin.test.*

class PortablePreparationTest {
    private val download = AirliftConfig("user@host", "/source/", Path("destination"), direction = TransferDirection.DOWNLOAD)
    private fun listing(match: SourceMatch, sizes: Map<String, Long>, single: Boolean = false, directories: Set<String> = emptySet()) =
        SourceListing(match, FileInventory(single, sizes, directories))

    @Test fun `remote mask discovers relative names and adjusts source root`() = kotlinx.coroutines.test.runTest {
        val plan = prepareTransfer(download.copy(remoteRoot = "/db/*.MYD"), inventory = { query ->
            assertEquals("/db/", query.base)
            assertEquals(1, query.maxDepth)
            listing(SourceMatch.MASK, mapOf("small.MYD" to 2L, "big.MYD" to 12L, "sub/hidden.MYD" to 50L, "index.MYI" to 30L))
        })
        assertEquals("/db/", plan.config.remoteRoot)
        assertEquals(listOf("big.MYD", "small.MYD"), plan.tasks.map { it.path })
        assertEquals(listOf(12L, 2L), plan.tasks.map { it.size })
    }

    @Test fun `single file source uses parent root`() = kotlinx.coroutines.test.runTest {
        val plan = prepareTransfer(download.copy(remoteRoot = "/db/one"), inventory = {
            listing(SourceMatch.LITERAL, mapOf("one" to 23L), single = true)
        })
        assertEquals("/db", plan.config.remoteRoot)
        assertEquals(23L, plan.tasks.single().size)
        assertEquals("one", plan.tasks.single().path)
    }

    @Test fun `masks support recursive and component matching`() = kotlinx.coroutines.test.runTest {
        assertTrue(fileMask("**/*.MY[DI]").matches("a.MYD"))
        assertTrue(fileMask("**/*.MY[DI]").matches("deep/a.MYI"))
        assertFalse(fileMask("*.MYD").matches("deep/a.MYD"))
        assertTrue(fileMask("a?.[!x]").matches("ab.y"))
        assertFalse(fileMask("a?.[!x]").matches("ab.x"))
    }

    @Test fun `mask escapes brackets and leading dots follow documented rules`() {
        assertTrue(fileMask("file\\[1\\].bin").matches("file[1].bin"))
        assertFalse(fileMask("file\\[1\\].bin").matches("file1.bin"))
        assertTrue(fileMask("a\\*b\\?c\\\\d").matches("a*b?c\\d"))
        assertTrue(fileMask("[]a]x").matches("]x"))
        assertTrue(fileMask("[!]a]x").matches("bx"))
        assertFalse(fileMask("[!]a]x").matches("]x"))
        // An unterminated class is literal text, like a shell glob.
        assertTrue(fileMask("[!]").matches("[!]"))
        assertTrue(fileMask("a[").matches("a["))
        assertTrue(fileMask("[a-c]-[^0-9]").matches("b-x"))
        assertFalse(fileMask("[!a]").matches("/"))
        assertTrue(fileMask("[&&^\\-]").matches("-"))
        assertTrue(fileMask("*").matches(".hidden"))
        assertFailsWith<IllegalArgumentException> { fileMask("[[:alpha:]]") }
        assertFailsWith<IllegalArgumentException> { fileMask("[a/b]") }
    }

    @Test fun `source query separates escaped literals masks and depth`() {
        assertEquals(SourceQuery("/srv/x"), sourceQuery("/srv/x", true))
        val bracket = sourceQuery("/srv/Photos [2020]/", true)
        assertEquals("/srv/Photos [2020]/", bracket.path)
        assertEquals("/srv/", bracket.base)
        assertEquals("Photos [2020]/**", bracket.mask)
        assertNull(bracket.maxDepth)
        val escaped = sourceQuery("/srv/Photos \\[2020\\]/*.jpg", true)
        assertEquals("/srv/Photos [2020]/", escaped.base)
        assertEquals("*.jpg", escaped.mask)
        assertEquals(1, escaped.maxDepth)
        assertEquals("/srv/a[b", sourceQuery("/srv/a\\[b").unescaped)
        assertNull(sourceQuery("/srv/a[b").base)
        assertEquals(2, sourceQuery("/srv/*/x.bin", true).maxDepth)
        assertNull(sourceQuery("/srv/*/x.bin", false).maxDepth)
        assertNull(sourceQuery("/srv/**/x.bin", true).maxDepth)
        assertEquals("./", sourceQuery("*.bin").base)
        assertEquals("/srv/a[1]/", sourceBase("/srv/a\\[1\\]/*.x"))
    }

    @Test fun `an existing bracketed path is literal and a missing one is a mask`() = kotlinx.coroutines.test.runTest {
        val literal = prepareTransfer(download.copy(remoteRoot = "/srv/Photos [2020]/"), inventory = {
            listing(SourceMatch.LITERAL, mapOf("a.jpg" to 3L), directories = setOf(""))
        })
        assertEquals("/srv/Photos [2020]/", literal.config.remoteRoot)
        assertEquals(listOf("a.jpg"), literal.tasks.map { it.path })
        val file = prepareTransfer(download.copy(remoteRoot = "/srv/file[1].bin"), inventory = {
            listing(SourceMatch.LITERAL, mapOf("file[1].bin" to 5L), single = true)
        })
        assertEquals("file[1].bin", file.tasks.single().path)
        assertEquals("/srv", file.config.remoteRoot)
    }

    @Test fun `masks matching no files fail and directory matches are explained`() = kotlinx.coroutines.test.runTest {
        val none = assertFailsWith<IllegalArgumentException> {
            prepareTransfer(download.copy(remoteRoot = "/srv/2024-*"), inventory = {
                listing(SourceMatch.MASK, mapOf("other.txt" to 1L), directories = setOf("", "2024-01", "2024-02"))
            })
        }
        assertContains(none.message.orEmpty(), "matched no files")
        assertContains(none.message.orEmpty(), "2 directories")
        val plan = prepareTransfer(download.copy(remoteRoot = "/srv/*"), inventory = {
            listing(SourceMatch.MASK, mapOf("top.txt" to 1L), directories = setOf("", "sub"))
        })
        assertEquals(listOf("top.txt"), plan.tasks.map { it.path })
        assertContains(plan.warnings.single(), "1 directories")
        val directories = prepareTransfer(download.copy(remoteRoot = "/srv/*/data/"), inventory = { query ->
            assertEquals("*/data/**", query.mask)
            listing(SourceMatch.MASK, mapOf("a/data/x" to 1L, "a/data/deep/y" to 2L, "a/other" to 3L), directories = setOf("", "a", "a/data"))
        })
        assertEquals(setOf("a/data/x", "a/data/deep/y"), directories.tasks.map { it.path }.toSet())
        assertFailsWith<IllegalArgumentException> {
            prepareTransfer(download.copy(remoteRoot = "/srv/empty/"), inventory = { listing(SourceMatch.LITERAL, emptyMap(), directories = setOf("")) })
        }
    }

    @Test fun `remote discovery script is valid for every login shell and encodes paths as octal`() {
        val path = "/data/a'b;touch nope \$HOME \\\\x!\"`%"
        for (query in listOf(SourceQuery(path), sourceQuery("$path/*.bin", true), sourceQuery("/srv/a\\[1\\]"))) {
            val command = inventoryCommand(query)
            assertTrue(command.startsWith("sh -c '") && command.endsWith("'"))
            val script = command.removePrefix("sh -c '").removeSuffix("'")
            // fish treats \\ and \' specially, csh treats ! and newlines specially: none may appear.
            assertTrue(script.none { it == '\'' || it == '!' || it == '\n' })
            assertFalse("\\\\" in script)
            assertFalse("touch nope" in script)
            assertContains(script, "-ignore_readdir_race")
        }
        val mask = inventoryCommand(sourceQuery("/srv/*.bin", true))
        assertContains(mask, "-maxdepth 1 ")
        assertContains(mask, "printf \"M\\0\"")
        assertContains(inventoryCommand(SourceQuery("/a b")), "printf \"/a\\040b\"")
        assertContains(inventoryCommand(sourceQuery("/srv/a\\[1\\]")), "printf \"UF\\0\"")
    }

    @Test fun `inventory decoder retains files directories symlinks and special entries`() = kotlinx.coroutines.test.runTest {
        val inventory = decodeInventory(listOf("D", "d\t0\t", "f\t42\tfile", "l\t7\tlink", "d\t0\tsub", "s\t0\tsocket"))
        assertFalse(inventory.singleFile)
        assertEquals(mapOf("file" to 42L, "link" to 7L), inventory.sizes)
        assertEquals(setOf("", "sub"), inventory.directories)
        assertEquals(setOf("socket"), inventory.otherEntries)
        assertEquals(mapOf("one" to 23L), decodeInventory(listOf("F", "23\tone")).sizes)
        assertFailsWith<IllegalArgumentException> { decodeInventory(listOf("invalid")) }
        assertFailsWith<IllegalArgumentException> { decodeInventory(listOf("D", "broken")) }
        assertFailsWith<IllegalArgumentException> { decodeInventory(listOf("D", "f\tx\tname")) }
    }

    @Test fun `streamed records may split anywhere and invalid UTF-8 names are skipped and block their directory`() {
        val bytes = "M\u0000d\t0\t\u0000f\t3\tok/a\u0000".encodeToByteArray() +
            "f\t4\tok/".encodeToByteArray() + byteArrayOf(0xff.toByte(), 'x'.code.toByte(), 0) +
            "d\t0\tbad".encodeToByteArray() + byteArrayOf(0xc3.toByte(), 0) +
            "f\t1\tbad".encodeToByteArray() + byteArrayOf(0xc3.toByte()) + "/inner\u0000".encodeToByteArray() +
            "f\t5\tп/b\u0000".encodeToByteArray()
        for (fragment in listOf(1, 3, 7, bytes.size)) {
            val decoder = InventoryDecoder()
            bytes.toList().chunked(fragment).forEach { decoder.accept(it.toByteArray()) }
            val listing = decoder.finish()
            assertEquals(SourceMatch.MASK, listing.match)
            assertEquals(mapOf("ok/a" to 3L, "п/b" to 5L), listing.inventory.sizes)
            assertEquals(setOf("ok", ""), listing.inventory.incomplete)
            assertEquals(listOf("ok/\\xFFx", "bad\\xC3"), listing.inventory.skipped)
            assertEquals(2, listing.inventory.skippedCount)
        }
        val unfinished = InventoryDecoder().apply { accept("D\u0000f\t1\tx".encodeToByteArray()) }
        assertFailsWith<IllegalStateException> { unfinished.finish() }
    }

    @Test fun `skipped names produce a warning and never collapse their parent`() = kotlinx.coroutines.test.runTest {
        val plan = prepareTransfer(download.copy(remoteRoot = "/srv/"), inventory = {
            SourceListing(SourceMatch.LITERAL, FileInventory(false, mapOf("d/a" to 1L, "d/b" to 1L), setOf("", "d"),
                incomplete = setOf("d"), skipped = listOf("d/\\xFF"), skippedCount = 1))
        })
        assertContains(plan.warnings.single(), "/srv/d/\\xFF")
        val job = batchSmallFiles(plan.tasks).single()
        assertTrue(DirectoryCollapser(plan.inventories).optimize(job).entries.isEmpty())
    }

    @Test fun `escaped names show invalid bytes and control characters`() {
        assertEquals("a\\x0Ab\\xFF\\\\c", escapedName(byteArrayOf('a'.code.toByte(), 10, 'b'.code.toByte(), 0xff.toByte(), '\\'.code.toByte(), 'c'.code.toByte())))
        assertEquals("имя", escapedName("имя".encodeToByteArray()))
    }

    @Test fun `CLI accepts masks without a manifest`() = kotlinx.coroutines.test.runTest {
        assertTrue("--run" in LaunchArguments.parse(listOf("user@host:/db/*.MYD", "/destination/")).enabled)
    }

    @Test fun `lexical absolute paths normalize dot segments and keep masks`() {
        assertEquals("/work/a", absoluteLocal("a") { "/work" })
        assertEquals("/work", absoluteLocal(".") { "/work" })
        assertEquals("/", absoluteLocal("..") { "/work" })
        assertEquals("/srv/*.bin", absoluteLocal("/srv/./x/../*.bin"))
        assertEquals("/srv/dir/", absoluteLocal("/srv/dir/"))
        assertEquals("C:/data/x", absoluteLocal("C:/data/./x"))
        assertEquals("//server/share", absoluteLocal("//server/share"))
    }
}
