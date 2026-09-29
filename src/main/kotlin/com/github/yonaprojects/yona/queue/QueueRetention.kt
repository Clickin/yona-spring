package com.github.yonaprojects.yona.queue

import io.micrometer.core.instrument.MeterRegistry
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.IOException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.Path

internal data class QueueRetentionResult(val jobs: Int, val audit: Int, val resourceLocks: Int) {
    val any get() = jobs + audit + resourceLocks > 0
}

/** Bounded, restartable history cleanup. Only SUCCEEDED/CANCELLED jobs are ever deleted. */
@Component
class QueueRetention(
    private val entityManager: EntityManager,
    transactionManager: PlatformTransactionManager,
    private val clock: QueueClock,
    private val store: QueueWorkerStore,
    meterRegistry: MeterRegistry,
    @Value("\${yona.queue.retention.terminal-days:30}") private val terminalDays: Int = 30,
    @Value("\${yona.queue.retention.audit-days:365}") private val auditDays: Int = 365,
    @Value("\${yona.queue.retention.batch:500}") private val batch: Int = 500,
    @Value("\${yona.queue.retention.interval-millis:3600000}") val intervalMillis: Long = 3_600_000,
) {
    private val transactions = TransactionTemplate(transactionManager)
    private val readTransactions = TransactionTemplate(transactionManager).apply { isReadOnly = true }
    private val jobCounter = meterRegistry.counter(METRIC, "kind", "job")
    private val auditCounter = meterRegistry.counter(METRIC, "kind", "audit")
    private val lockCounter = meterRegistry.counter(METRIC, "kind", "resource_lock")

    init {
        require(terminalDays in 0..MAX_DAYS) { "Queue terminal retention must be between 0 and $MAX_DAYS days" }
        require(auditDays in 0..MAX_DAYS) { "Queue audit retention must be between 0 and $MAX_DAYS days" }
        require(batch in 1..5000) { "Queue retention batch must be between 1 and 5000" }
        require(intervalMillis >= 1000) { "Queue retention interval must be at least 1000 milliseconds" }
    }

    /** Runs each step even if an earlier one failed; the first failure is rethrown afterwards. */
    internal fun runPass(): QueueRetentionResult {
        clock.checkSynchronized()
        val now = clock.now()
        var failure: Exception? = null
        fun step(block: () -> Int): Int = try {
            block()
        } catch (thrown: Exception) {
            val first = failure
            if (first == null) failure = thrown else first.addSuppressed(thrown)
            0
        }
        val jobs = step { deleteJobs(now) }
        val audit = step { deleteAudit(now) }
        val locks = step { deleteResourceLocks() }
        val result = QueueRetentionResult(jobs, audit, locks)
        if (result.any) {
            logger.info("Queue retention deleted {} job(s), {} audit row(s), {} resource lock(s)", jobs, audit, locks)
        }
        failure?.let { throw it }
        return result
    }

    private fun deleteJobs(now: Long): Int {
        if (terminalDays == 0) return 0
        val cutoff = now - terminalDays * DAY_MILLIS
        val ids = readTransactions.execute {
            entityManager.createQuery(
                "select j.id from QueueJob j where j.status in :statuses and j.finishedAt < :cutoff order by j.id",
                Long::class.javaObjectType,
            ).setParameter("statuses", RETAINED_STATUSES).setParameter("cutoff", cutoff)
                .setMaxResults(batch).resultList
        }.orEmpty()
        var deleted = 0
        var failure: Exception? = null
        for (id in ids) {
            if (Thread.currentThread().isInterrupted) break
            try {
                if (deleteJob(id, cutoff)) deleted++
            } catch (thrown: Exception) {
                if (failure == null) failure = thrown else failure.addSuppressed(thrown)
            }
        }
        failure?.let { throw it }
        return deleted
    }

    private fun deleteJob(id: Long, cutoff: Long): Boolean = inTransaction {
        // Enqueue locks the idempotency row before the job row; keep that order to avoid a deadlock.
        entityManager.createQuery("select k from QueueIdempotencyKey k where k.job.id = :id", QueueIdempotencyKey::class.java)
            .setParameter("id", id).setLockMode(LockModeType.PESSIMISTIC_WRITE).resultList
        // Same row lock as publication and orphan cleanup, so this is safe across nodes.
        val job = entityManager.find(QueueJob::class.java, id, LockModeType.PESSIMISTIC_WRITE)
            ?: return@inTransaction false
        val finishedAt = job.finishedAt
        if (job.status !in RETAINED_STATUSES || finishedAt == null || finishedAt >= cutoff) return@inTransaction false

        val paths = entityManager.createQuery(
            "select a.storagePath from QueueArtifact a where a.job.id = :id", String::class.java,
        ).setParameter("id", id).resultList
        // Files go first. A failure here rolls the transaction back and the job is retried next pass.
        deleteArtifactFiles(paths)
        for (query in listOf(
            "delete from QueueArtifact a where a.job.id = :id",
            "delete from QueueAttempt a where a.id.jobId = :id",
            "delete from QueueJobResource r where r.id.jobId = :id",
            "delete from QueueIdempotencyKey k where k.job.id = :id",
            "delete from QueueJob j where j.id = :id",
        )) entityManager.createQuery(query).setParameter("id", id).executeUpdate()
        store.bumpProjectionChange()
        countAfterCommit(jobCounter, 1)
        true
    }

    private fun deleteArtifactFiles(storagePaths: List<String>) {
        val files = storagePaths.map(::artifactFile)
        files.forEach { Files.deleteIfExists(it) }
        files.forEach { removeEmptyDirectories(it.parent) }
    }

    private fun artifactFile(storagePath: String): Path {
        val root = store.root
        val file = root.resolve(storagePath).normalize()
        require(file.startsWith(store.artifactRoot) && file != store.artifactRoot) { "Unsafe queue artifact path" }
        var current = root
        for (part in root.relativize(file.parent)) {
            current = current.resolve(part)
            require(!Files.isSymbolicLink(current)) { "Unsafe queue artifact path" }
        }
        return file
    }

    private fun removeEmptyDirectories(start: Path) {
        var directory: Path? = start
        while (directory != null && directory != store.artifactRoot && directory.startsWith(store.artifactRoot)) {
            try {
                Files.deleteIfExists(directory)
            } catch (_: DirectoryNotEmptyException) {
                return
            } catch (failure: IOException) {
                // An empty leftover directory is harmless; orphan cleanup handles it.
                logger.debug("Queue retention could not remove directory {}", directory, failure)
                return
            }
            directory = directory.parent
        }
    }

    private fun deleteAudit(now: Long): Int {
        if (auditDays == 0) return 0
        val cutoff = now - auditDays * DAY_MILLIS
        val ids = readTransactions.execute {
            entityManager.createQuery(
                "select a.commandId from QueueAdminAudit a where a.createdAt < :cutoff order by a.createdAt, a.commandId",
                String::class.java,
            ).setParameter("cutoff", cutoff).setMaxResults(batch).resultList
        }.orEmpty()
        if (ids.isEmpty()) return 0
        return inTransaction {
            var deleted = 0
            // Chunked to stay under per-statement bind limits (SQL Server allows 2100).
            for (chunk in ids.chunked(500)) {
                deleted += entityManager.createQuery(
                    "delete from QueueAdminAudit a where a.commandId in :ids and a.createdAt < :cutoff",
                ).setParameter("ids", chunk).setParameter("cutoff", cutoff).executeUpdate()
            }
            countAfterCommit(auditCounter, deleted)
            deleted
        }
    }

    private fun deleteResourceLocks(): Int {
        val keys = readTransactions.execute {
            entityManager.createQuery(
                "select l.resourceKey from QueueResourceLock l where l.currentJobId is null and " +
                    "l.currentAttemptNo is null and l.currentFence is null and l.leaseExpiresAt is null and " +
                    "not exists (select 1 from QueueJobResource r where r.id.resourceKey = l.resourceKey) " +
                    "order by l.resourceKey",
                String::class.java,
            ).setMaxResults(batch).resultList
        }.orEmpty()
        var deleted = 0
        for (key in keys) {
            if (Thread.currentThread().isInterrupted) break
            if (deleteResourceLock(key)) deleted++
        }
        return deleted
    }

    /**
     * Dropping the fence tombstone is safe because the fence never leaves the queue: owns() also requires
     * jobId and attemptNo, job ids are never reused (next-id is monotonic) and attempt numbers grow per job,
     * so a stale token can not match a recreated row. Resource fences are internal only (QueueAttemptToken is
     * internal and TaskContext exposes just the job fence). If they are ever exposed as external fencing
     * tokens, this deletion must be revisited. A claim that finds the row gone recreates it in lockResourceRows.
     */
    private fun deleteResourceLock(key: String): Boolean = inTransaction {
        val lock = entityManager.find(QueueResourceLock::class.java, key, LockModeType.PESSIMISTIC_WRITE)
            ?: return@inTransaction false
        val unowned = lock.currentJobId == null && lock.currentAttemptNo == null &&
            lock.currentFence == null && lock.leaseExpiresAt == null
        val referenced = entityManager.createQuery(
            "select count(r) from QueueJobResource r where r.id.resourceKey = :key", Long::class.javaObjectType,
        ).setParameter("key", key).singleResult > 0
        if (!unowned || referenced) return@inTransaction false
        entityManager.remove(lock)
        entityManager.flush()
        countAfterCommit(lockCounter, 1)
        true
    }

    private fun countAfterCommit(counter: io.micrometer.core.instrument.Counter, amount: Int) {
        if (amount == 0) return
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = counter.increment(amount.toDouble())
        })
    }

    private fun <T> inTransaction(block: () -> T): T {
        var value: Any? = null
        transactions.executeWithoutResult { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    companion object {
        private val logger = LoggerFactory.getLogger(QueueRetention::class.java)
        private const val METRIC = "yona.queue.retention.deleted"
        private const val DAY_MILLIS = 86_400_000L
        private const val MAX_DAYS = 36_500
        private val RETAINED_STATUSES = listOf(QueueStatus.SUCCEEDED, QueueStatus.CANCELLED)
    }
}
