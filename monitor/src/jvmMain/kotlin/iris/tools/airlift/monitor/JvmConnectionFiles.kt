package iris.tools.airlift.monitor

import kotlinx.io.files.Path
import java.io.ByteArrayOutputStream
import java.nio.file.FileAlreadyExistsException
import java.security.SecureRandom
import java.util.Properties

private val random = SecureRandom()

/** 256 random bits, hex encoded like the native token. */
internal actual fun monitorToken(): String =
    ByteArray(32).also(random::nextBytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

internal actual fun readConnectionFile(path: Path): Map<String, String> = Properties().apply {
    MonitorFiles.inputStream(path).use { load(it) }
}.let { properties -> properties.stringPropertyNames().associateWith { properties.getProperty(it) } }

internal actual fun writeConnectionFile(path: Path, values: Map<String, String>) {
    path.absolutePath().parent?.let { MonitorFiles.createDirectories(it) }
    val content = ByteArrayOutputStream().also { output ->
        Properties().apply { values.forEach { (key, value) -> setProperty(key, value) } }
            .store(output, "Rsync Airlift local monitor connection")
    }.toByteArray()
    try { MonitorFiles.writePrivateFile(path, content) }
    catch (error: FileAlreadyExistsException) {
        throw IllegalStateException("Connection file already exists: ${path.absolutePath()}. " +
            "Use --attach to view that run, or --endpoint with a different file for a new run. " +
            "If it is stale, remove it only after verifying the old dispatcher has stopped.", error)
    }
}
internal actual fun deleteConnectionFile(path: Path) { MonitorFiles.deleteIfExists(path) }
