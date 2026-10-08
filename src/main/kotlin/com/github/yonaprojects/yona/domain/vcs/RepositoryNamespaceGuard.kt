package com.github.yonaprojects.yona.domain.vcs

import org.springframework.beans.factory.annotation.Value
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale

/** Coordinates name allocation across VCS roots; project owner/name has no database unique key. */
@Component
class RepositoryNamespaceGuard(
    @Value("\${yona.data:data}") private val dataDir: String,
    @Value("\${yona.svn.base-dir:/tmp/yona/svn}") private val svnBaseDir: String
) {
    fun acquire(owner: String, name: String): AutoCloseable {
        val configured = Path.of(dataDir).toAbsolutePath().normalize()
        Files.createDirectories(configured)
        val directory = configured.toRealPath().resolve("repository-names")
        Files.createDirectories(directory)
        check(directory.toRealPath() == directory) { "Unsafe repository namespace lock directory" }
        val path = directory.resolve("${key(owner, name)}.lock")
        check(!Files.exists(path, NOFOLLOW_LINKS) || Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Unsafe repository namespace lock file" }
        val channel = FileChannel.open(path, CREATE, WRITE, NOFOLLOW_LINKS)
        try {
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            check(lock != null) { "Repository name is busy; retry after the current operation finishes" }
            // Stable inode: neither normal completion nor failure unlinks this lock file.
            return AutoCloseable { try { lock.release() } finally { channel.close() } }
        } catch (failure: Exception) {
            channel.close()
            throw failure
        }
    }

    fun holdUntilTransactionCompletion(owner: String, name: String) {
        check(TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.isSynchronizationActive()) {
            "Repository name allocation requires an active transaction"
        }
        val resource = "repository-namespace:${Path.of(dataDir).toAbsolutePath().normalize()}:${key(owner, name)}"
        if (TransactionSynchronizationManager.hasResource(resource)) return
        val held = acquire(owner, name)
        try {
            TransactionSynchronizationManager.bindResource(resource, held)
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCompletion(status: Int) {
                    try { TransactionSynchronizationManager.unbindResourceIfPossible(resource) } finally { held.close() }
                }
            })
        } catch (failure: Exception) {
            TransactionSynchronizationManager.unbindResourceIfPossible(resource)
            held.close()
            throw failure
        }
    }

    fun requireUnreserved(owner: String, name: String) {
        if (Files.exists(markerPath(owner, name), NOFOLLOW_LINKS)) {
            throw AccessDeniedException("Repository name is reserved by a mirror; operator inspection is required")
        }
    }

    fun markerPath(owner: String, name: String): Path = Path.of(svnBaseDir).toAbsolutePath().normalize()
        .resolve(".mirror-owners").resolve("${key(owner, name)}.owner")

    private fun key(owner: String, name: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest((owner.lowercase(Locale.ROOT) + '\u0000' + name.lowercase(Locale.ROOT)).toByteArray(Charsets.UTF_8))
    )
}
