package com.github.yonaprojects.yona.queue

import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.pool.HikariPool
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.hibernate.exception.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.time.Instant
import java.security.MessageDigest
import java.util.HexFormat
import javax.sql.DataSource
import tools.jackson.core.JacksonException

class QueueAdmissionException(val code: String) : RuntimeException(code)
enum class QueueReceiptCommitState { PROVISIONAL }

/** Local wake hint only: durable state remains in the business database. */
data object QueueWorkAvailable

/** Only the caller's outer transaction commit makes this receipt durable. */
data class ProvisionalQueueReceipt(
    val jobId: Long,
    val status: QueueStatus,
    val scheduledAt: Instant,
    val commitState: QueueReceiptCommitState = QueueReceiptCommitState.PROVISIONAL,
)

data class QueueJobSnapshot(
    val id: Long,
    val taskType: String,
    val payloadVersion: Int,
    val status: QueueStatus,
    val createdAt: Long,
    val scheduledAt: Long,
    val executionGeneration: Long,
    val generationAttemptNo: Int,
    val attemptCount: Long,
    val rowVersion: Long,
)

@Service
class Queue(
    private val entityManager: EntityManager,
    private val transactionManager: PlatformTransactionManager,
    private val clock: QueueClock,
    private val registry: TaskRegistry,
    private val dataSource: DataSource,
    @Value("\${yona.queue.max-pending:10000}") private val maxPending: Long = 10_000,
    @Value("\${yona.queue.max-payload-bytes:1048576}") private val maxPayloadBytes: Int = 1_048_576,
    private val events: ApplicationEventPublisher? = null,
) : InitializingBean {
    private val admissionResourceKey = Any()

    private class PendingAdmissions(val committed: Long, var admitted: Long = 0)

    override fun afterPropertiesSet() {
        require(maxPending > 0 && maxPayloadBytes in 1..1_048_576)
        try {
            checkNotNull(dataSource.unwrap(HikariDataSource::class.java))
        } catch (failure: Exception) {
            throw IllegalStateException("Yona durable queue requires HikariCP", failure)
        }
        try {
            clock.verifySynchronization()
        } catch (untrusted: QueueAdmissionException) {
            if (untrusted.code != "CLOCK_UNTRUSTED") throw untrusted
            LoggerFactory.getLogger(Queue::class.java).warn("Queue admission disabled: database clock is untrusted")
        }
        val transaction = TransactionTemplate(transactionManager)
        try {
            transaction.executeWithoutResult {
                for (name in listOf("next-id", "change-generation")) {
                    if (entityManager.find(QueueCounter::class.java, name) == null) {
                        entityManager.persist(QueueCounter(name))
                    }
                }
                entityManager.flush()
            }
        } catch (race: ConstraintViolationException) {
            val initialized = transaction.execute {
                entityManager.find(QueueCounter::class.java, "next-id") != null &&
                    entityManager.find(QueueCounter::class.java, "change-generation") != null
            }
            if (initialized != true) throw race
        }
        transaction.executeWithoutResult {
            entityManager.find(QueueCounter::class.java, "change-generation", LockModeType.PESSIMISTIC_WRITE)
            if (entityManager.find(QueueCounter::class.java, FAILED_COUNTER) == null) {
                // Cold upgrade only: all old nodes must be stopped before this one-time backfill.
                val failed = entityManager.createQuery(
                    "select count(j.id) from QueueJob j where j.status = :status", Long::class.javaObjectType,
                ).setParameter("status", QueueStatus.FAILED).singleResult
                entityManager.persist(QueueCounter(FAILED_COUNTER, failed))
            }
        }
    }

    @Transactional
    fun enqueue(
        type: String,
        version: Int,
        payload: ByteArray,
        dueAt: Instant,
        idempotencyKey: String?,
        callerScope: String,
    ): ProvisionalQueueReceipt {
        clock.checkSynchronized()
        if (!TaskRegistry.TYPE.matches(type) || version < 1 || payload.size > maxPayloadBytes) {
            throw QueueAdmissionException("INVALID_REQUEST")
        }
        val scopeBytes = identityBytes(callerScope)
        val keyBytes = idempotencyKey?.let(::identityBytes)
        val scheduledAt = try {
            // Millisecond storage must round a fractional not-before instant up, never execute early.
            Math.addExact(dueAt.toEpochMilli(), if (dueAt.nano % 1_000_000 == 0) 0 else 1)
        } catch (_: ArithmeticException) {
            throw QueueAdmissionException("INVALID_REQUEST")
        }
        // Own the admitted bytes: a caller can mutate its array after enqueue but before outer commit.
        val admittedPayload = payload.copyOf()
        val payloadHash = HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(admittedPayload))
        val keyDigest = keyBytes?.let {
            val digest = MessageDigest.getInstance("SHA-256")
            digestPart(digest, type.toByteArray(Charsets.UTF_8))
            digestPart(digest, scopeBytes)
            digestPart(digest, it)
            HEX.formatHex(digest.digest())
        }

        // ponytail: serialize admission on one counter; partition only after measured contention.
        val updated = entityManager.createQuery(
            "update QueueCounter c set c.value = c.value + 1 where c.name = :name and c.value < :maximum"
        ).setParameter("name", "next-id").setParameter("maximum", Long.MAX_VALUE).executeUpdate()
        if (updated != 1) throw QueueAdmissionException("ID_EXHAUSTED")
        if (keyDigest != null) {
            // Both rows need current reads. A fetch join on MySQL locks only the root alias.
            val existing = entityManager.find(QueueIdempotencyKey::class.java, keyDigest, LockModeType.PESSIMISTIC_WRITE)
            if (existing != null) {
                if (existing.taskType != type || existing.callerScope != callerScope ||
                    existing.requestKey != idempotencyKey || existing.payloadVersion != version ||
                    existing.payloadHash != payloadHash) {
                    throw QueueAdmissionException("IDEMPOTENCY_CONFLICT")
                }
                val job = checkNotNull(existing.job)
                entityManager.refresh(job, LockModeType.PESSIMISTIC_READ)
                if (!job.payload.contentEquals(admittedPayload)) throw QueueAdmissionException("IDEMPOTENCY_CONFLICT")
                return ProvisionalQueueReceipt(job.id, job.status, Instant.ofEpochMilli(job.scheduledAt))
            }
        }
        requirePendingCapacity()
        val resources = registry.find(type, version)?.let { definition ->
            try {
                registry.validate(definition, admittedPayload)
            } catch (_: JacksonException) {
                throw QueueAdmissionException("INVALID_PAYLOAD")
            } catch (_: IllegalArgumentException) {
                throw QueueAdmissionException("INVALID_PAYLOAD")
            }
        } ?: emptyList()
        val id = entityManager.createQuery(
            "select c.value from QueueCounter c where c.name = :name", Long::class.javaObjectType
        ).setParameter("name", "next-id").singleResult
        val now = clock.now()
        val job = QueueJob(
            id = id, taskType = type, payloadVersion = version, payload = admittedPayload,
            payloadHash = payloadHash, createdAt = now, updatedAt = now, scheduledAt = scheduledAt,
        )
        entityManager.persist(job)
        if (keyDigest != null) {
            entityManager.persist(QueueIdempotencyKey(
                keyDigest, type, callerScope, idempotencyKey!!, version, payloadHash, job,
            ))
        }
        resources.forEach { entityManager.persist(QueueJobResource(QueueJobResourceId(id, it), job)) }
        val changed = entityManager.createQuery(
            "update QueueCounter c set c.value = c.value + 1 where c.name = :name and c.value < :maximum"
        ).setParameter("name", "change-generation").setParameter("maximum", Long.MAX_VALUE).executeUpdate()
        if (changed != 1) throw QueueAdmissionException("VERSION_EXHAUSTED")
        entityManager.flush()
        recordPendingAdmission()
        return ProvisionalQueueReceipt(id, QueueStatus.QUEUED, Instant.ofEpochMilli(scheduledAt))
    }

    @Transactional(readOnly = true)
    fun find(jobId: Long): QueueJobSnapshot? = entityManager.createQuery(
        "select new com.github.yonaprojects.yona.queue.QueueJobSnapshot(" +
            "j.id,j.taskType,j.payloadVersion,j.status,j.createdAt,j.scheduledAt," +
            "j.executionGeneration,j.generationAttemptNo,j.attemptCount,j.rowVersion) " +
            "from QueueJob j where j.id = :id", QueueJobSnapshot::class.java
    ).setParameter("id", jobId).singleResultOrNull

    /** Caller holds the next-id row lock shared by admission and manual retry. */
    internal fun requirePendingCapacity() {
        val pending = pendingAdmissions()
        if (pending.committed >= maxPending - pending.admitted) throw QueueAdmissionException("QUEUE_FULL")
    }

    /** Called only after a new pending job or a manual retry has been successfully persisted. */
    internal fun recordPendingAdmission() {
        val pending = pendingAdmissions()
        pending.admitted = Math.addExact(pending.admitted, 1L)
    }

    private fun pendingAdmissions(): PendingAdmissions {
        val existing = TransactionSynchronizationManager.getResource(admissionResourceKey) as? PendingAdmissions
        if (existing != null) return existing
        check(TransactionSynchronizationManager.isSynchronizationActive())
        // Holding next-id excludes other producers/retries; workers can only reduce this upper bound.
        // Read once before our inserts so lock-based READ COMMITTED cannot wait on our own transaction.
        val pending = PendingAdmissions(pendingJobs())
        TransactionSynchronizationManager.bindResource(admissionResourceKey, pending)
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun suspend() {
                TransactionSynchronizationManager.unbindResource(admissionResourceKey)
            }
            override fun resume() {
                TransactionSynchronizationManager.bindResource(admissionResourceKey, pending)
            }
            override fun afterCommit() {
                if (pending.admitted > 0) events?.publishEvent(QueueWorkAvailable)
            }
            override fun afterCompletion(status: Int) {
                TransactionSynchronizationManager.unbindResourceIfPossible(admissionResourceKey)
            }
        })
        return pending
    }

    private fun pendingJobs(): Long {
        try {
            // Hikari's native timed borrow avoids both global timeout changes and abandoned async acquisitions.
            val source = dataSource.unwrap(HikariDataSource::class.java)
            val pool = source.hikariPoolMXBean as? HikariPool ?: throw QueueAdmissionException("QUEUE_UNAVAILABLE")
            return pool.getConnection(2000).use { connection ->
                connection.autoCommit = true
                connection.createStatement().use { statement ->
                    statement.queryTimeout = 5
                    statement.executeQuery(
                        "SELECT COUNT(*) FROM queue_job WHERE status IN " +
                            "('QUEUED','RUNNING','RETRY_WAIT','CANCEL_REQUESTED')"
                    ).use { rows -> check(rows.next()); rows.getLong(1) }
                }
            }
        } catch (_: SQLException) {
            throw QueueAdmissionException("QUEUE_UNAVAILABLE")
        }
    }

    private fun identityBytes(value: String): ByteArray {
        if (!Charsets.UTF_8.newEncoder().canEncode(value)) throw QueueAdmissionException("INVALID_REQUEST")
        return value.toByteArray(Charsets.UTF_8).also {
            if (it.size > 200) throw QueueAdmissionException("INVALID_REQUEST")
        }
    }

    private fun digestPart(digest: MessageDigest, bytes: ByteArray) {
        digest.update((bytes.size ushr 24).toByte())
        digest.update((bytes.size ushr 16).toByte())
        digest.update((bytes.size ushr 8).toByte())
        digest.update(bytes.size.toByte())
        digest.update(bytes)
    }

    companion object {
        private val HEX = HexFormat.of()
        internal const val FAILED_COUNTER = "failed-jobs"

        internal fun updateFailedCount(entityManager: EntityManager, before: QueueStatus, after: QueueStatus) {
            val delta = (if (after == QueueStatus.FAILED) 1 else 0) - (if (before == QueueStatus.FAILED) 1 else 0)
            if (delta == 0) return
            val changed = entityManager.createQuery(
                "update QueueCounter c set c.value = c.value + :delta where c.name = :name and c.value >= :minimum",
            ).setParameter("name", FAILED_COUNTER).setParameter("delta", delta.toLong())
                .setParameter("minimum", if (delta < 0) 1L else 0L).executeUpdate()
            check(changed == 1) { "Queue failed-job counter is unavailable" }
        }
    }
}
