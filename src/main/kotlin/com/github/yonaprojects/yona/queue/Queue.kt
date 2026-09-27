package com.github.yonaprojects.yona.queue

import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.hibernate.exception.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.security.MessageDigest
import java.util.HexFormat
import javax.sql.DataSource
import tools.jackson.core.JacksonException

class QueueAdmissionException(val code: String) : RuntimeException(code)
enum class QueueReceiptCommitState { PROVISIONAL }

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
    dataSource: DataSource,
    @Value("\${yona.queue.max-pending:10000}") private val maxPending: Long = 10_000,
    @Value("\${yona.queue.max-payload-bytes:1048576}") private val maxPayloadBytes: Int = 1_048_576,
) : InitializingBean {
    private val jdbc = JdbcTemplate(dataSource)

    override fun afterPropertiesSet() {
        require(maxPending > 0 && maxPayloadBytes in 1..1_048_576)
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
        if (pendingJobs() >= maxPending) throw QueueAdmissionException("QUEUE_FULL")
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
        return ProvisionalQueueReceipt(id, QueueStatus.QUEUED, Instant.ofEpochMilli(scheduledAt))
    }

    @Transactional(readOnly = true)
    fun find(jobId: Long): QueueJobSnapshot? = entityManager.createQuery(
        "select new com.github.yonaprojects.yona.queue.QueueJobSnapshot(" +
            "j.id,j.taskType,j.payloadVersion,j.status,j.createdAt,j.scheduledAt," +
            "j.executionGeneration,j.generationAttemptNo,j.attemptCount,j.rowVersion) " +
            "from QueueJob j where j.id = :id", QueueJobSnapshot::class.java
    ).setParameter("id", jobId).singleResultOrNull

    private fun pendingJobs(): Long = jdbc.execute(ConnectionCallback { connection ->
        val product = connection.metaData.databaseProductName
        val lockingRead = if (product == "MySQL" || product == "MariaDB") " FOR UPDATE" else ""
        connection.createStatement().use { statement ->
            statement.queryTimeout = 5
            statement.executeQuery(
                "SELECT COUNT(*) FROM queue_job WHERE status IN " +
                    "('QUEUED','RUNNING','RETRY_WAIT','CANCEL_REQUESTED')" + lockingRead
            ).use { rows -> check(rows.next()); rows.getLong(1) }
        }
    })

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
    }
}
