package com.github.yonaprojects.yona.domain.vcs

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

@Component
class RepositoryMirrorLock(@Value("\${yona.svn.base-dir:/tmp/yona/svn}") private val baseDir: String) {
    fun <T> withLock(projectId: Long, action: () -> T): T {
        require(projectId > 0)
        val configured = Path.of(baseDir).toAbsolutePath().normalize()
        Files.createDirectories(configured)
        val base = configured.toRealPath()
        val locks = base.resolve(".mirror-locks")
        Files.createDirectories(locks)
        if (locks.toRealPath() != locks) throw MirrorFailure("UNSAFE_LOCK_PATH", attention = true)
        val path = locks.resolve("$projectId.lock")
        if (Files.exists(path, NOFOLLOW_LINKS) && !Files.isRegularFile(path, NOFOLLOW_LINKS)) throw MirrorFailure("UNSAFE_LOCK_PATH", attention = true)
        // Never unlink this inode, including on shutdown or lease loss.
        FileChannel.open(path, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (lock == null) throw MirrorFailure("WRITER_STILL_ACTIVE", attention = true)
            lock.use { return action() }
        }
    }
}
