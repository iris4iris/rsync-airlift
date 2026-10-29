package iris.tools.airlift.monitor

import iris.tools.airlift.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class FilePreparationTest {
    private fun config(root: Path, direction: TransferDirection = TransferDirection.UPLOAD) =
        AirliftConfig("user@host", "/source/", root.toPortablePath(), direction = direction)
    private fun portable(path: Path) = path.toString().replace('\\', '/')

    @Test fun `local discovery measures all files and schedules largest first`() = kotlinx.coroutines.test.runTest {
        val root = Files.createTempDirectory("airlift-plan-")
        try {
            Files.write(root.resolve("small"), ByteArray(2))
            Files.createDirectories(root.resolve("sub"))
            Files.write(root.resolve("sub/large"), ByteArray(20))
            val plan = prepareTransfer(config(root))
            assertEquals(listOf("sub/large", "small"), plan.tasks.map { it.path })
            assertEquals(listOf(20L, 2L), plan.tasks.map { it.size })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `mask and manifest union removes duplicates and retains files outside mask`() = kotlinx.coroutines.test.runTest {
        val root = Files.createTempDirectory("airlift-plan-")
        try {
            Files.write(root.resolve("a.MYD"), ByteArray(2))
            Files.write(root.resolve("b.MYI"), ByteArray(12))
            Files.write(root.resolve("ignored"), ByteArray(30))
            val manifest = root.resolve("files.txt")
            Files.writeString(manifest, "a.MYD\r\nb.MYI\r\na.MYD\r\n")
            val plan = prepareTransfer(config(root), manifest.toPortablePath(), source = "$root/*.MYD".replace('\\', '/'))
            assertEquals(listOf("b.MYI", "a.MYD"), plan.tasks.map { it.path })
            assertEquals(root.toPortablePath(), plan.config.localRoot)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `manifest alone selects only listed files and supports NUL paths`() = kotlinx.coroutines.test.runTest {
        val root = Files.createTempDirectory("airlift-plan-")
        try {
            Files.write(root.resolve("with space"), ByteArray(3))
            Files.write(root.resolve("other"), ByteArray(10))
            val manifest = root.resolve("list")
            Files.writeString(manifest, "with space\u0000")
            assertEquals(listOf("with space"), prepareTransfer(config(root), manifest.toPortablePath(), true).tasks.map { it.path })
            Files.writeString(manifest, "missing\n")
            assertFailsWith<IllegalArgumentException> { prepareTransfer(config(root), manifest.toPortablePath()) }
            Files.writeString(manifest, "../other\n")
            assertFailsWith<IllegalArgumentException> { prepareTransfer(config(root), manifest.toPortablePath()) }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `manifest accepts a UTF-8 BOM and find-style leading dot slash`() = kotlinx.coroutines.test.runTest {
        val root = Files.createTempDirectory("airlift-plan-")
        try {
            Files.createDirectories(root.resolve("sub"))
            Files.write(root.resolve("sub/a"), ByteArray(3))
            Files.write(root.resolve("b"), ByteArray(4))
            val manifest = root.resolve("list")
            Files.write(manifest, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "./sub/a\n././b\n./\n".toByteArray())
            assertEquals(listOf("b", "sub/a"), prepareTransfer(config(root), manifest.toPortablePath()).tasks.map { it.path })
            Files.write(manifest, "./sub/a\u0000./b\u0000".toByteArray())
            assertEquals(2, prepareTransfer(config(root), manifest.toPortablePath(), true).tasks.size)
            Files.writeString(manifest, "/etc/passwd\n")
            assertContains(assertFailsWith<IllegalArgumentException> { prepareTransfer(config(root), manifest.toPortablePath()) }.message.orEmpty(),
                "Invalid --files entry")
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `existing bracketed local names are literal and missing masks fail`() = kotlinx.coroutines.test.runTest {
        val root = Files.createTempDirectory("airlift-plan-")
        try {
            Files.write(root.resolve("file[1].bin"), ByteArray(5))
            Files.write(root.resolve("file1.bin"), ByteArray(7))
            val literal = prepareTransfer(config(root), source = portable(root.resolve("file[1].bin")))
            assertEquals(listOf("file[1].bin" to 5L), literal.tasks.map { it.path to it.size })
            val mask = prepareTransfer(config(root), source = portable(root) + "/file[0-9].bin")
            assertEquals(listOf("file1.bin"), mask.tasks.map { it.path })
            val failure = assertFailsWith<IllegalArgumentException> { prepareTransfer(config(root), source = portable(root) + "/*.none") }
            assertContains(failure.message.orEmpty(), "matched no files")
            val directory = Files.createDirectories(root.resolve("Photos [2020]"))
            Files.write(directory.resolve("a.jpg"), ByteArray(1))
            assertEquals(listOf("a.jpg"), prepareTransfer(config(root), source = portable(directory) + "/").tasks.map { it.path })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `depth limited local inventory reports unvisited directories that block collapse`() = kotlinx.coroutines.test.runTest {
        val root = Files.createTempDirectory("airlift-plan-")
        try {
            Files.write(root.resolve("a.iso"), ByteArray(1))
            Files.write(root.resolve("b.iso"), ByteArray(1))
            Files.createDirectories(root.resolve("sub/deeper"))
            Files.write(root.resolve("sub/deeper/c.iso"), ByteArray(1))
            val inventory = readLocalInventory(root.toString(), maxDepth = 1)
            assertEquals(setOf("a.iso", "b.iso"), inventory.sizes.keys)
            assertEquals(setOf("", "sub"), inventory.directories)
            val plan = prepareTransfer(config(root), source = portable(root) + "/*.iso")
            assertEquals(setOf("a.iso", "b.iso"), plan.tasks.map { it.path }.toSet())
            val job = batchSmallFiles(plan.tasks).single()
            assertTrue(DirectoryCollapser(plan.inventories).optimize(job).entries.isEmpty())
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `trailing slash follows a symlinked source directory and masks below it`() = kotlinx.coroutines.test.runTest {
        val root = Files.createTempDirectory("airlift-plan-")
        try {
            val target = Files.createDirectories(root.resolve("target"))
            Files.write(target.resolve("x.bin"), ByteArray(3))
            val link = root.resolve("link")
            try { Files.createSymbolicLink(link, target) }
            catch (_: java.nio.file.FileSystemException) { return@runTest }
            catch (_: UnsupportedOperationException) { return@runTest }
            val contents = prepareTransfer(config(root), source = portable(link) + "/")
            assertEquals(listOf("x.bin"), contents.tasks.map { it.path })
            assertEquals(listOf("x.bin"), prepareTransfer(config(root), source = portable(link) + "/*.bin").tasks.map { it.path })
            val itself = prepareTransfer(config(root), source = portable(link))
            assertEquals(listOf("link"), itself.tasks.map { it.path })
            assertEquals(root.toPortablePath(), itself.config.localRoot)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `remote inventory shell command executes with quoted paths and exact sizes`() = kotlinx.coroutines.test.runTest {
        val bash = Path.of("C:/Program Files/Git/bin/bash.exe")
        if (!Files.exists(bash)) return@runTest // Optional Windows integration with the bundled GNU find.
        val parent = Files.createTempDirectory("airlift-shell-")
        fun run(query: SourceQuery): SourceListing {
            val process = ProcessBuilder(bash.toString(), "-s").start()
            process.outputStream.bufferedWriter().use { it.write(inventoryCommand(query)) }
            val output = process.inputStream.readAllBytes()
            val error = process.errorStream.readAllBytes().toString(Charsets.UTF_8)
            assertEquals(0, process.waitFor(), error)
            return InventoryDecoder().apply { accept(output) }.finish()
        }
        try {
            val root = Files.createDirectory(parent.resolve("a'b; \$HOME [x] literal"))
            Files.write(root.resolve("with space"), ByteArray(17))
            Files.createDirectory(root.resolve("nested"))
            Files.write(root.resolve("nested/other"), ByteArray(5))
            // Git Bash spells C:/x as /c/x; the script itself requires absolute POSIX paths.
            fun msys(path: Path) = Regex("^([A-Za-z]):/").replace(portable(path)) { "/" + it.groupValues[1].lowercase() + "/" }
            val path = msys(root)
            val literal = run(sourceQuery(path))
            assertEquals(SourceMatch.LITERAL, literal.match)
            assertEquals(mapOf("with space" to 17L, "nested/other" to 5L), literal.inventory.sizes)
            assertEquals(setOf("", "nested"), literal.inventory.directories)
            assertEquals(SourceMatch.LITERAL, run(sourceQuery("$path/nested/other")).match)
            val mask = run(sourceQuery(path.replace("[", "\\[").replace("]", "\\]") + "/*", true))
            assertEquals(SourceMatch.MASK, mask.match)
            assertEquals(mapOf("with space" to 17L), mask.inventory.sizes) // depth 1: nested/other is not listed
            assertEquals(setOf("", "nested"), mask.inventory.directories)
            val escaped = msys(parent) + "/a'b; \$HOME \\[x\\] literal/with space"
            val unescaped = run(sourceQuery(escaped))
            assertEquals(SourceMatch.UNESCAPED, unescaped.match)
            assertEquals(mapOf("with space" to 17L), unescaped.inventory.sizes)
            val missing = ProcessBuilder(bash.toString(), "-s").start()
            missing.outputStream.bufferedWriter().use { it.write(inventoryCommand(SourceQuery("$path/none"))) }
            assertEquals(3, missing.waitFor())
        } finally { parent.toFile().deleteRecursively() }
    }
}
