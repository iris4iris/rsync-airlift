package iris.tools.airlift.process

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.files.Path
import kotlin.test.*

class JvmProcessTest {
    private val launcher = JvmProcessLauncher()
    private fun command(mode: String, vararg args: String): List<String> {
        val classes = ProcessTestChild::class.java.protectionDomain.codeSource.location.toURI().let { java.nio.file.Path.of(it).toString() }
        val stdlib = Unit::class.java.protectionDomain.codeSource.location.toURI().let { java.nio.file.Path.of(it).toString() }
        val executable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString()
        return listOf(executable, "-cp", classes + java.io.File.pathSeparator + stdlib, ProcessTestChild::class.java.name, mode) + args
    }
    private suspend fun ProcessByteSource.bytes(): ByteArray {
        val result = Buffer()
        val bytes = ByteArray(8192)
        while (true) { val count = read(bytes); if (count < 0) break; result.write(bytes, 0, count) }
        return result.readByteArray()
    }

    @Test fun `binary stdin and stdout work when called from Default`() = runBlocking<Unit> {
        withContext(Dispatchers.Default) {
            val process = launcher.start(ProcessSpec(command("echo")))
            try {
                val expected = "Файл 😀\u0000\r\n".repeat(10000).encodeToByteArray()
                val output = async { process.stdout!!.bytes() }
                process.stdin!!.write(expected); process.stdin!!.close()
                assertContentEquals(expected, output.await())
                assertEquals(0, process.awaitExit())
            } finally { process.close() }
        }
    }

    @Test fun `bidirectional pipes do not deadlock with output larger than OS buffers`() = runBlocking<Unit> {
        withTimeout(10_000) {
            val process = launcher.start(ProcessSpec(command("duplex")))
            try {
                val output = async { process.stdout!!.bytes() }
                val input = ByteArray(512 * 1024) { 7 }
                process.stdin!!.write(input); process.stdin!!.close()
                val result = output.await()
                assertContentEquals(ByteArray(512 * 1024) { 42 } + input, result)
                assertEquals(0, process.awaitExit())
            } finally { process.close() }
        }
    }

    @Test fun `many children blocked on reads do not starve the writes that feed them`() = runBlocking<Unit> {
        // sort prints nothing until stdin closes. With readers started first, 80 blocked reads exceed
        // Dispatchers.IO's 64 threads; the writes must still run or every child waits forever.
        withTimeout(60_000) {
            val processes = (1..80).map { launcher.start(ProcessSpec(listOf("sort"))) }
            try {
                val outputs = processes.map { process -> async(Dispatchers.Default) { process.stdout!!.bytes() } }
                delay(500)
                processes.map { process ->
                    async(Dispatchers.Default) { process.stdin!!.write("b\r\na\r\n".encodeToByteArray()); process.stdin!!.close() }
                }.awaitAll()
                outputs.awaitAll().forEach { assertEquals(listOf("a", "b"), it.decodeToString().lines().map(String::trim).filter(String::isNotEmpty)) }
            } finally { processes.forEach { it.close() } }
        }
    }

    @Test fun `stderr can be separate or merged`() = runBlocking<Unit> {
        for (merge in listOf(false, true)) {
            val process = launcher.start(ProcessSpec(command("separate"), mergeError = merge))
            try {
                val out = process.stdout!!.bytes().decodeToString()
                if (merge) { assertEquals("outerr", out); assertNull(process.stderr) }
                else { assertEquals("out", out); assertEquals("err", process.stderr!!.bytes().decodeToString()) }
                assertEquals(0, process.awaitExit())
            } finally { process.close() }
        }
    }

    @Test fun `arguments environment and working directory are passed without shell evaluation`() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("airlift-process-")
        try {
            val literal = "a b; \$(not-a-command)"
            val process = launcher.start(ProcessSpec(command("inspect", literal), environment = mapOf("AIRLIFT_TEST" to "test value"), directory = Path(directory.toString())))
            try {
                assertEquals("$literal\ntest value\n$directory", process.stdout!!.bytes().decodeToString())
                assertEquals(0, process.awaitExit())
            } finally { process.close() }
        } finally { Files.delete(directory) }
    }

    @Test fun `exit code timeout and explicit termination have distinct semantics`() = runBlocking<Unit> {
        val exited = launcher.start(ProcessSpec(command("exit")))
        try { assertEquals(23, exited.awaitExit()); assertEquals(23, exited.exitCode()) }
        finally { exited.close() }
        val process = launcher.start(ProcessSpec(command("block")))
        try {
            assertNull(process.awaitExit(25))
            assertNull(process.exitCode())
            process.terminate(force = true)
            assertNotNull(withTimeout(5000) { process.awaitExit() })
        } finally { process.close() }
    }

    @Test fun `cancelling a blocked pipe read releases the child`() = runBlocking<Unit> {
        val process = launcher.start(ProcessSpec(command("block")))
        try {
            val reader = async(Dispatchers.Default) { process.stdout!!.read(ByteArray(1)) }
            delay(100)
            reader.cancelAndJoin()
            withTimeout(5000) { process.awaitExit() }
        } finally { process.close() }
    }

    @Test fun `file redirection supports input output and append`() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("airlift-redirect-")
        try {
            val input = directory.resolve("input")
            val output = directory.resolve("output")
            Files.writeString(input, "hello")
            repeat(2) {
                val process = launcher.start(ProcessSpec(command("echo"), input = ProcessRedirect.File(Path(input.toString())),
                    output = ProcessRedirect.File(Path(output.toString()), append = it > 0)))
                try { assertNull(process.stdin); assertNull(process.stdout); assertEquals(0, process.awaitExit()) }
                finally { process.close() }
            }
            assertEquals("hellohello", Files.readString(output))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun `concurrent close waits for process cleanup and is idempotent`() = runBlocking<Unit> {
        val process = launcher.start(ProcessSpec(command("block")))
        coroutineScope { repeat(8) { launch(Dispatchers.Default) { process.close() } } }
        assertNotNull(process.exitCode())
        process.close()
    }

    @Test fun `SSH password override stays in process environment and is redacted from descriptions`() {
        try {
            iris.tools.airlift.SshAuthentication.usePassword("replacement password".toCharArray())
            val spec = iris.tools.airlift.SshAuthentication.configure(ProcessSpec(listOf("ssh", "-o", "BatchMode=yes"),
                environment = mapOf("SSHPASS" to "wrong password")))
            assertEquals("replacement password", spec.environment["SSHPASS"])
            assertEquals(listOf("sshpass", "-e", "ssh", "-o", "BatchMode=no"), spec.command)
            assertFalse(spec.toString().contains("replacement password"))
            assertFalse(spec.command.any { "password" in it })
        } finally { iris.tools.airlift.SshAuthentication.clearPassword() }
    }
}
