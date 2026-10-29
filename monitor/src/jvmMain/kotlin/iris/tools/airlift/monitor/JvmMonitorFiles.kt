package iris.tools.airlift.monitor

import iris.tools.airlift.io.JvmFileAccess
import kotlinx.io.files.Path
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import java.util.EnumSet

internal object MonitorFiles {
    fun temporaryDirectory(prefix: String): Path = JvmFileAccess.temporaryDirectory(prefix).toPortablePath()
    fun temporaryFile(prefix: String, suffix: String): Path = JvmFileAccess.temporaryFile(prefix, suffix).toPortablePath()
    fun createDirectories(path: Path) = JvmFileAccess.createDirectories(path)
    fun createPrivateFile(path: Path) = JvmFileAccess.createPrivateFile(path.toNioPath())
    fun inputStream(path: Path) = JvmFileAccess.inputStream(path.toNioPath())
    fun outputStream(path: Path) = JvmFileAccess.outputStream(path.toNioPath())
    fun readText(path: Path) = JvmFileAccess.readText(path)
    fun bufferedWriter(path: Path) = JvmFileAccess.bufferedWriter(path)
    fun deleteIfExists(path: Path) = JvmFileAccess.deleteIfExists(path.toNioPath())

    /**
     * Creates [path] exclusively and writes [bytes] through the same channel: the file is never reopened by
     * name and an existing file or link is never followed. POSIX files are created 0600. Elsewhere an
     * owner-only ACL is applied at creation when supported, else before the content is written; a file system
     * without ACLs (for example FAT) keeps its default permissions.
     */
    fun writePrivateFile(path: Path, bytes: ByteArray) {
        val file = path.toNioPath()
        val options = setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        val views = file.fileSystem.supportedFileAttributeViews()
        val posix = "posix" in views
        val acl = !posix && "acl" in views
        val attributes: Array<FileAttribute<*>> = when {
            posix -> arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            acl -> listOfNotNull(currentUser(file)?.let { ownerOnly(listOf(it)) }).toTypedArray()
            else -> emptyArray()
        }
        val channel = try { Files.newByteChannel(file, options, *attributes) }
            catch (_: UnsupportedOperationException) { Files.newByteChannel(file, options) }
        var written = false
        try {
            channel.use {
                // Restrict before any secret reaches the file; best-effort where ACLs are unavailable.
                if (acl) runCatching { restrictToOwner(file) }
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) it.write(buffer)
            }
            written = true
        } finally { if (!written) Files.deleteIfExists(file) }
    }

    private fun currentUser(file: java.nio.file.Path): UserPrincipal? = runCatching {
        file.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
    }.getOrNull()

    private fun ownerOnly(principals: List<UserPrincipal>) = object : FileAttribute<List<AclEntry>> {
        override fun name() = "acl:acl"
        override fun value(): List<AclEntry> = principals.distinct().map {
            AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(it)
                .setPermissions(EnumSet.allOf(AclEntryPermission::class.java)).build()
        }
    }

    /** Replaces the DACL with full control for the owner and the current user only. */
    private fun restrictToOwner(file: java.nio.file.Path) {
        val view = Files.getFileAttributeView(file, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) ?: return
        view.acl = ownerOnly(listOfNotNull(view.owner, currentUser(file))).value()
    }
}
