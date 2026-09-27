package com.github.yonaprojects.yona.queue

import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserState
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

class QueueControlException(val code: String) : RuntimeException(code)

data class QueueControlResult(
    val jobId: Long,
    val status: QueueStatus,
    val commandId: String,
    val deduplicated: Boolean = false,
)

@Service
class QueueControl(
    private val entityManager: EntityManager,
    transactionManager: PlatformTransactionManager,
    private val clock: QueueClock,
    private val registry: TaskRegistry,
    private val store: QueueWorkerStore,
    private val runtime: QueueWorkerRuntime,
) {
    private val transactions = TransactionTemplate(transactionManager)

    fun cancel(jobId: Long, commandId: String, actorId: Long, reason: String? = null): QueueControlResult =
        command(jobId, commandId, actorId, "CANCEL", recoveryAcknowledged = false, reason = reason) { job, definition, now ->
            val prior = job.status
            val attemptOutcome = job.activeAttemptNo?.let { attemptNo ->
                entityManager.find(QueueAttempt::class.java, QueueAttemptId(job.id, attemptNo), LockModeType.PESSIMISTIC_WRITE)?.outcome
            }
            if (prior == QueueStatus.RUNNING && attemptOutcome != AttemptOutcome.RUNNING) {
                throw QueueControlException("STALE_ATTEMPT")
            }
            val decision = decide(
                job, attemptOutcome, QueueTransitionEvent.Cancel,
                QueueTransitionPolicy(definition?.maxAttempts ?: 5, definition?.handler != null, definition?.replaySafe == true),
            )
            if (!decision.accepted) throw QueueControlException(decision.errorCode ?: "INVALID_TRANSITION")
            if (decision.status != prior) apply(job, decision, now)
            prior == QueueStatus.RUNNING && decision.status == QueueStatus.CANCEL_REQUESTED
        }

    fun retry(
        jobId: Long,
        commandId: String,
        actorId: Long,
        recoveryAcknowledged: Boolean = false,
        reason: String? = null,
    ): QueueControlResult = command(
        jobId, commandId, actorId, "RETRY", recoveryAcknowledged, reason,
    ) { job, definition, now ->
        val task = definition?.takeIf { it.handler != null } ?: throw QueueControlException("UNSUPPORTED_HANDLER")
        if (job.status == QueueStatus.RECOVERY_REQUIRED && recoveryAcknowledged && reason.isNullOrBlank()) {
            throw QueueControlException("RECOVERY_REASON_REQUIRED")
        }
        if (job.status == QueueStatus.FAILED || job.status == QueueStatus.RECOVERY_REQUIRED ||
            job.status == QueueStatus.BLOCKED_UNSUPPORTED) {
            ensureRetryPayloadAndResources(job, task)
        }
        val decision = decide(
            job, null, QueueTransitionEvent.ManualRetry(recoveryAcknowledged),
            QueueTransitionPolicy(task.maxAttempts, handlerRegistered = true, replaySafe = task.replaySafe),
        )
        if (!decision.accepted) throw QueueControlException(decision.errorCode ?: "INVALID_TRANSITION")
        apply(job, decision, now)
        false
    }

    private fun command(
        jobId: Long,
        rawCommandId: String,
        actorId: Long,
        action: String,
        recoveryAcknowledged: Boolean,
        reason: String?,
        change: (QueueJob, TaskDefinition?, Long) -> Boolean,
    ): QueueControlResult {
        if (jobId <= 0 || actorId <= 0) throw QueueControlException("INVALID_REQUEST")
        val commandId = canonicalCommandId(rawCommandId)
        validateReason(reason)
        val hash = commandHash(jobId, actorId, action, recoveryAcknowledged, reason)
        val result = try {
            executeCommand(jobId, commandId, actorId, action, recoveryAcknowledged, reason, hash, change)
        } catch (failure: RuntimeException) {
            if (!failure.hasConstraintViolation()) throw failure
            val existing = findAudit(commandId) ?: throw failure
            if (existing.commandHash != hash) throw QueueControlException("COMMAND_ID_CONFLICT")
            val current = findJobStatus(jobId) ?: throw QueueControlException("NOT_FOUND")
            return QueueControlResult(jobId, current, commandId, deduplicated = true)
        }
        return result
    }

    private fun executeCommand(
        jobId: Long,
        commandId: String,
        actorId: Long,
        action: String,
        recoveryAcknowledged: Boolean,
        reason: String?,
        hash: String,
        change: (QueueJob, TaskDefinition?, Long) -> Boolean,
    ): QueueControlResult = inTransaction {
        val actor = entityManager.find(User::class.java, actorId, LockModeType.PESSIMISTIC_READ)
        if (actor?.state != UserState.SITE_ADMIN) throw QueueControlException("FORBIDDEN")

        val already = entityManager.find(QueueAdminAudit::class.java, commandId, LockModeType.PESSIMISTIC_WRITE)
        if (already != null) {
            if (already.commandHash != hash) throw QueueControlException("COMMAND_ID_CONFLICT")
            val job = entityManager.find(QueueJob::class.java, jobId) ?: throw QueueControlException("NOT_FOUND")
            return@inTransaction QueueControlResult(jobId, job.status, commandId, true)
        }

        val job = entityManager.find(QueueJob::class.java, jobId, LockModeType.PESSIMISTIC_WRITE)
            ?: throw QueueControlException("NOT_FOUND")
        // A same-job command can have committed while this transaction waited on the job row.
        val afterLock = entityManager.find(QueueAdminAudit::class.java, commandId, LockModeType.PESSIMISTIC_WRITE)
        if (afterLock != null) {
            if (afterLock.commandHash != hash) throw QueueControlException("COMMAND_ID_CONFLICT")
            return@inTransaction QueueControlResult(jobId, job.status, commandId, true)
        }

        val definition = registry.find(job.taskType, job.payloadVersion)
        val prior = job.status
        val now = clock.now()
        val signal = change(job, definition, now)
        store.bumpProjectionChange()
        entityManager.persist(QueueAdminAudit(
            commandId = commandId, commandHash = hash, jobId = jobId, actorUserId = actorId,
            action = action, recoveryAcknowledged = recoveryAcknowledged, reason = reason,
            priorStatus = prior, newStatus = job.status, createdAt = now,
        ))
        entityManager.flush()
        if (signal) TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = runtime.signalCancellation(jobId)
        })
        QueueControlResult(jobId, job.status, commandId)
    }

    private fun ensureRetryPayloadAndResources(job: QueueJob, definition: TaskDefinition) {
        val decoded = try {
            registry.decodeAndValidate(definition, job.payload)
        } catch (_: Exception) {
            throw QueueControlException("UNSUPPORTED_HANDLER")
        }
        val existing = entityManager.createQuery(
            "select r.id.resourceKey from QueueJobResource r where r.id.jobId = :jobId order by r.id.resourceKey",
            String::class.java,
        ).setParameter("jobId", job.id).resultList
        if (existing == decoded.resourceKeys) return
        if (job.status != QueueStatus.BLOCKED_UNSUPPORTED || existing.isNotEmpty()) {
            throw QueueControlException("UNSUPPORTED_HANDLER")
        }
        decoded.resourceKeys.forEach { key ->
            entityManager.persist(QueueJobResource(QueueJobResourceId(job.id, key), job))
        }
        entityManager.flush()
    }

    private fun decide(
        job: QueueJob,
        outcome: AttemptOutcome?,
        event: QueueTransitionEvent,
        policy: QueueTransitionPolicy,
    ): QueueTransitionDecision = QueueTransition.decide(
        QueueTransitionInput(
            QueueTransitionState(
                job.status, job.executionGeneration, job.generationAttemptNo, job.attemptCount,
                job.failureDisposition, outcome,
            ),
            event,
            policy,
        ),
    )

    private fun apply(job: QueueJob, decision: QueueTransitionDecision, now: Long) {
        val oldStatus = job.status
        job.status = decision.status
        job.executionGeneration = decision.executionGeneration
        job.generationAttemptNo = decision.generationAttemptNo
        job.attemptCount = decision.attemptCount
        job.failureDisposition = decision.failureDisposition
        job.updatedAt = now
        if (decision.status != QueueStatus.RUNNING && decision.status != QueueStatus.CANCEL_REQUESTED) {
            job.activeAttemptNo = null
        }
        if (decision.status != QueueStatus.RETRY_WAIT) job.nextAttemptAt = null
        job.finishedAt = if (decision.status in TERMINAL) now else null
        if (decision.status == QueueStatus.QUEUED && oldStatus != QueueStatus.QUEUED) {
            job.scheduledAt = now
            job.progressStage = null
            job.progressJson = null
            job.errorCode = null
            job.errorSummary = null
        }
    }

    private fun validateReason(reason: String?) {
        if (reason != null && (reason.toByteArray(Charsets.UTF_8).size > 1024 || reason.any { it.code < 0x20 && it != '\t' })) {
            throw QueueControlException("INVALID_REASON")
        }
    }

    private fun canonicalCommandId(value: String): String {
        val parsed = runCatching { UUID.fromString(value) }.getOrElse { throw QueueControlException("INVALID_COMMAND_ID") }
        if (!value.matches(Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) {
            throw QueueControlException("INVALID_COMMAND_ID")
        }
        return parsed.toString()
    }

    private fun commandHash(
        jobId: Long,
        actorId: Long,
        action: String,
        recoveryAcknowledged: Boolean,
        reason: String?,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(jobId.toString(), actorId.toString(), action, recoveryAcknowledged.toString()).forEach { part ->
            digestPart(digest, part.toByteArray(Charsets.UTF_8))
        }
        digestPart(digest, reason?.toByteArray(Charsets.UTF_8) ?: byteArrayOf(0xff.toByte()))
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun digestPart(digest: MessageDigest, bytes: ByteArray) {
        digest.update((bytes.size ushr 24).toByte())
        digest.update((bytes.size ushr 16).toByte())
        digest.update((bytes.size ushr 8).toByte())
        digest.update(bytes.size.toByte())
        digest.update(bytes)
    }

    private fun findAudit(commandId: String): QueueAdminAudit? = inTransaction {
        entityManager.find(QueueAdminAudit::class.java, commandId)
    }

    private fun findJobStatus(jobId: Long): QueueStatus? = inTransaction {
        entityManager.find(QueueJob::class.java, jobId)?.status
    }

    private fun <T> inTransaction(block: () -> T): T {
        var value: Any? = null
        transactions.executeWithoutResult { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun Throwable.hasConstraintViolation(): Boolean = generateSequence(this) { it.cause }.any {
        it is ConstraintViolationException || it is DataIntegrityViolationException
    }

    companion object {
        private val TERMINAL = setOf(
            QueueStatus.SUCCEEDED, QueueStatus.FAILED, QueueStatus.RECOVERY_REQUIRED,
            QueueStatus.BLOCKED_UNSUPPORTED, QueueStatus.CANCELLED,
        )
    }
}
