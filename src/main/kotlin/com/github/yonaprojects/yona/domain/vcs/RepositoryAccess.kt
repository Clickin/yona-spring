package com.github.yonaprojects.yona.domain.vcs

import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.locks.Lock
import kotlin.concurrent.write

class RepositoryUnavailableException : IllegalStateException("Repository is unavailable")

/** Coordinates repository sessions with reset in this serving JVM, not other processes or nodes. */
object RepositoryAccess {
    // Keep lock identities stable even while callers are queued. Different projects never share a lock.
    private val locks = ConcurrentHashMap<Long, ReentrantReadWriteLock>()
    // ponytail: reset pauses all repository sessions; narrower gates require tracking shared fork inodes.
    private val archiveBarrier = ReentrantReadWriteLock(true)

    inline fun <T> session(action: () -> T): T {
        val releaseOnExit = acquireArchiveRead()
        try {
            return action()
        } finally {
            releaseOnExit?.unlock()
        }
    }

    inline fun <T> read(projectId: Long, action: () -> T): T = session {
        val releaseOnExit = acquireRead(projectId)
        try {
            action()
        } finally {
            releaseOnExit?.unlock()
        }
    }

    inline fun <T> exclusive(projectId: Long, action: () -> T): T = session {
        val releaseOnExit = acquireExclusive(projectId)
        try {
            action()
        } finally {
            releaseOnExit?.unlock()
        }
    }

    inline fun <T> readBoth(firstId: Long?, secondId: Long?, action: () -> T): T = when {
        firstId == null -> if (secondId == null) action() else read(secondId, action)
        secondId == null || firstId == secondId -> read(firstId, action)
        firstId < secondId -> read(firstId) { read(secondId, action) }
        else -> read(secondId) { read(firstId, action) }
    }

    @PublishedApi
    internal fun acquireArchiveRead(): Lock? = acquire(archiveBarrier.readLock())

    @PublishedApi
    internal fun acquireRead(projectId: Long): Lock? =
        acquire(locks.computeIfAbsent(projectId) { ReentrantReadWriteLock(true) }.readLock())

    @PublishedApi
    internal fun acquireExclusive(projectId: Long): Lock? = acquire(writableLock(projectId).writeLock())

    private fun acquire(lock: Lock): Lock? {
        lock.lock()
        if (!TransactionSynchronizationManager.isActualTransactionActive() ||
            !TransactionSynchronizationManager.isSynchronizationActive()) {
            return lock
        }
        try {
            // Local JPA transaction callbacks run on this acquiring thread. Each acquisition owns one release.
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCompletion(status: Int) = lock.unlock()
            })
        } catch (failure: Throwable) {
            lock.unlock()
            throw failure
        }
        return null
    }

    fun <T> reset(projectId: Long, action: () -> T): T =
        session { writableLock(projectId).write(action) }

    fun <T> archive(projectId: Long, action: () -> T): T {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "Repository archive must begin before its database transaction"
        }
        check(archiveBarrier.readHoldCount == 0 || archiveBarrier.isWriteLockedByCurrentThread) {
            "Cannot archive from an active repository session"
        }
        return archiveBarrier.write { writableLock(projectId).write(action) }
    }

    private fun writableLock(projectId: Long): ReentrantReadWriteLock {
        val lock = locks.computeIfAbsent(projectId) { ReentrantReadWriteLock(true) }
        check(lock.readHoldCount == 0 || lock.isWriteLockedByCurrentThread) {
            "Cannot modify repository lifecycle from a repository read operation"
        }
        return lock
    }

    /** Canonicalizes existing ancestors too, so normal creation may use an absent leaf. */
    fun isWithinRoot(path: File, root: File): Boolean = try {
        path.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
