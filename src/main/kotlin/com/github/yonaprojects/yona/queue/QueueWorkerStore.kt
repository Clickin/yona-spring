package com.github.yonaprojects.yona.queue

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.hibernate.exception.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.json.JsonMapper
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.FileAlreadyExistsException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.min

internal data class QueueCandidateSnapshot(
    val id: Long,
    val rowVersion: Long,
    val taskType: String,
    val payloadVersion: Int,
    val payload: ByteArray,
    val resourceKeys: List<String>,
)

internal data class QueueDueCursor(val priority: Short, val id: Long)

@Component
class QueueWorkerStore(
    private val entityManager: EntityManager,
    transactionManager: PlatformTransactionManager,
    private val clock: QueueClock,
    private val registry: TaskRegistry,
    dataSource: javax.sql.DataSource,
    @Value("\${yona.queue.data-dir:\${yona.data:data}/queue}") dataDirectory: String,
    @Value("\${yona.queue.lease-millis:60000}") private val leaseMillis: Long = 60_000,
    @Value("\${yona.queue.error-summary-codepoints:2048}") private val errorSummaryCodePoints: Int = 2048,
    meterRegistry: MeterRegistry,
) {
    private val transactions = TransactionTemplate(transactionManager)
    private val jdbc = JdbcTemplate(dataSource)
    private val fencedTransactions = TransactionTemplate(transactionManager).apply {
        timeout = (leaseMillis / 4_000).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    }
    internal val root: Path = Path.of(dataDirectory.takeIf { it.isNotBlank() } ?: error("Queue data directory is required"))
        .toAbsolutePath().normalize().also { Files.createDirectories(it) }.toRealPath()
    private val stagingRoot = root.resolve("staging")
    internal val artifactRoot: Path = root.resolve("artifacts")
    private val cleanupCursor = root.resolve("cleanup.cursor")
    // A batch can stop inside a job; restart from that job after a process restart.
    private var cleanupStagingJobId = 0L
    private var cleanupStagingAfter: Path? = null
    private val progressMapper = JsonMapper.builder().build()
    private val retryCounter = meterRegistry.counter("yona.queue.retries")
    private val outcomeCounters = AttemptOutcome.entries.filter { it != AttemptOutcome.RUNNING }.associateWith {
        meterRegistry.counter("yona.queue.attempts", "outcome", it.name)
    }

    init {
        require(leaseMillis > 0)
        require(errorSummaryCodePoints in 1..2048)
        Files.createDirectories(stagingRoot)
        Files.createDirectories(artifactRoot)
    }

    internal fun dueCandidateIds(after: QueueDueCursor?, limit: Int, saturated: Set<String> = emptySet()): List<QueueDueCursor> {
        require(limit in 1..64)
        val now = clock.epochMillisSql(precise = false)
        val sql = buildString {
            append("SELECT priority, id FROM queue_job WHERE ((status = 'QUEUED' AND scheduled_at_epoch_ms <= $now) ")
            append("OR (status = 'RETRY_WAIT' AND next_attempt_at_epoch_ms <= $now))")
            if (after != null) append(" AND (priority < ? OR (priority = ? AND id > ?))")
            if (saturated.isNotEmpty()) append(" AND task_type NOT IN (${saturated.joinToString(",") { "?" }})")
            append(" ORDER BY priority DESC, id ASC")
        }
        return jdbc.execute(ConnectionCallback { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.queryTimeout = 5
                statement.maxRows = limit
                var index = 1
                if (after != null) {
                    statement.setShort(index++, after.priority)
                    statement.setShort(index++, after.priority)
                    statement.setLong(index++, after.id)
                }
                saturated.forEach { statement.setString(index++, it) }
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(QueueDueCursor(rows.getShort(1), rows.getLong(2))) }
                }
            }
        }) ?: emptyList()
    }

    internal fun expiredAttemptIds(afterId: Long, limit: Int = 64): List<Long> {
        require(limit in 1..64)
        val now = clock.epochMillisSql(precise = true)
        val sql = "SELECT j.id FROM queue_job j JOIN queue_attempt a ON a.job_id = j.id " +
            "AND a.attempt_no = j.active_attempt_no WHERE j.status IN ('RUNNING','CANCEL_REQUESTED') " +
            "AND a.outcome = 'RUNNING' AND a.lease_expires_at_epoch_ms <= $now AND j.id > ? ORDER BY j.id"
        val after = scanIds(sql, afterId, limit)
        return if (after.isEmpty() && afterId > 0) scanIds(sql, 0L, limit) else after
    }

    private fun scanIds(sql: String, afterId: Long, limit: Int): List<Long> =
        jdbc.execute(ConnectionCallback { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.queryTimeout = 5
                statement.maxRows = limit
                statement.setLong(1, afterId)
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(rows.getLong(1)) }
                }
            }
        }) ?: emptyList()

    internal fun candidate(id: Long): QueueCandidateSnapshot? = inTransaction {
        val job = entityManager.find(QueueJob::class.java, id) ?: return@inTransaction null
        val keys = entityManager.createQuery(
            "select r.id.resourceKey from QueueJobResource r where r.id.jobId = :jobId order by r.id.resourceKey",
            String::class.java,
        ).setParameter("jobId", id).resultList
        QueueCandidateSnapshot(
            job.id, job.rowVersion, job.taskType, job.payloadVersion, job.payload, keys,
        )
    }

    internal fun markUnsupported(
        candidate: QueueCandidateSnapshot,
        errorCode: String = "UNSUPPORTED_HANDLER",
        explanation: String = "No compatible executable handler is registered for this task version.",
    ): Boolean = inTransaction {
        val job = entityManager.find(QueueJob::class.java, candidate.id, LockModeType.PESSIMISTIC_WRITE)
            ?: return@inTransaction false
        val now = clock.now()
        if (job.rowVersion != candidate.rowVersion || !isDue(job, now)) return@inTransaction false
        val decision = QueueTransition.decide(
            QueueTransitionInput(
                stateOf(job), QueueTransitionEvent.ClaimDue(true, false),
                QueueTransitionPolicy(5, handlerRegistered = false, replaySafe = false),
            ),
        )
        if (!decision.accepted) return@inTransaction false
        applyDecision(job, decision, now)
        job.errorCode = errorCode
        job.errorSummary = boundedSummary(explanation)
        bumpProjectionChange()
        entityManager.flush()
        true
    }

    internal fun claim(
        candidate: QueueCandidateSnapshot,
        definition: TaskDefinition,
        decoded: DecodedTaskPayload,
        ownerInstance: String,
    ): QueueAttemptToken? {
        try {
            return inTransaction {
                val job = entityManager.find(QueueJob::class.java, candidate.id, LockModeType.PESSIMISTIC_WRITE)
                    ?: return@inTransaction null
                if (job.rowVersion != candidate.rowVersion || job.taskType != definition.type ||
                    job.payloadVersion != definition.payloadVersion || !isDue(job, clock.now()) ||
                    candidate.resourceKeys != decoded.resourceKeys) return@inTransaction null

                val state = stateOf(job)
                val decision = QueueTransition.decide(
                    QueueTransitionInput(
                        state, QueueTransitionEvent.ClaimDue(true, true),
                        QueueTransitionPolicy(definition.maxAttempts, handlerRegistered = true, replaySafe = definition.replaySafe),
                    ),
                )
                if (!decision.accepted) return@inTransaction null
                if (decision.status != QueueStatus.RUNNING) {
                    applyDecision(job, decision, clock.leaseNow())
                    bumpProjectionChange()
                    entityManager.flush()
                    return@inTransaction null
                }

                val attemptNo = decision.attemptCount
                val fence = Math.addExact(job.nextFence, 1L)
                val locks = lockResourceRows(candidate.resourceKeys)
                val now = clock.leaseNow()
                val leaseExpiresAt = Math.addExact(now, leaseMillis)
                if (locks.any { row ->
                        val expiry = row.leaseExpiresAt
                        (row.currentJobId == null && (row.currentAttemptNo != null || row.currentFence != null || expiry != null)) ||
                            (row.currentJobId != null && (row.currentAttemptNo == null || row.currentFence == null ||
                                expiry == null || expiry > now))
                    }) return@inTransaction null

                val resourceFences = LinkedHashMap<String, Long>(locks.size)
                for (row in locks) {
                    row.fenceCounter = Math.addExact(row.fenceCounter, 1L)
                    row.currentJobId = job.id
                    row.currentAttemptNo = attemptNo
                    row.currentFence = row.fenceCounter
                    row.leaseExpiresAt = leaseExpiresAt
                    resourceFences[row.resourceKey] = row.fenceCounter
                }

                applyDecision(job, decision, now)
                job.nextFence = fence
                job.activeAttemptNo = attemptNo
                job.progressStage = null
                job.progressJson = null
                val attempt = QueueAttempt(
                    id = QueueAttemptId(job.id, attemptNo), job = job,
                    executionGeneration = job.executionGeneration,
                    generationAttemptNo = job.generationAttemptNo,
                    fence = fence, ownerInstance = ownerInstance,
                    outcome = AttemptOutcome.RUNNING, startedAt = now, heartbeatAt = now,
                    leaseExpiresAt = leaseExpiresAt,
                )
                entityManager.persist(attempt)
                bumpProjectionChange()
                entityManager.flush()
                if (attemptNo > 1) countAfterCommit(retryCounter)
                QueueAttemptToken(
                    job.id, attemptNo, job.executionGeneration, fence, ownerInstance,
                    resourceFences, definition, decoded.node,
                )
            }
        } catch (failure: RuntimeException) {
            if (failure.hasConstraintViolation()) return null
            throw failure
        }
    }

    private fun lockResourceRows(keys: List<String>): List<QueueResourceLock> = keys.map { key ->
        val existing = entityManager.find(QueueResourceLock::class.java, key, LockModeType.PESSIMISTIC_WRITE)
        if (existing != null) existing else {
            val created = QueueResourceLock(resourceKey = key)
            entityManager.persist(created)
            entityManager.flush()
            created
        }
    }

    internal fun heartbeat(token: QueueAttemptToken): Boolean = inTransaction {
        if (token.stale.get()) return@inTransaction false
        val job = entityManager.find(QueueJob::class.java, token.jobId, LockModeType.PESSIMISTIC_WRITE)
            ?: return@inTransaction false
        val attempt = entityManager.find(
            QueueAttempt::class.java, QueueAttemptId(token.jobId, token.attemptNo), LockModeType.PESSIMISTIC_WRITE,
        ) ?: return@inTransaction false
        val locks = mutableListOf<QueueResourceLock>()
        for ((key, resourceFence) in token.resourceFences) {
            val lock = entityManager.find(QueueResourceLock::class.java, key, LockModeType.PESSIMISTIC_WRITE)
                ?: return@inTransaction false
            if (!owns(lock, token, resourceFence)) return@inTransaction false
            locks += lock
        }
        val now = clock.leaseNow()
        if (!isCurrent(token, job, attempt, now) || locks.any { (it.leaseExpiresAt ?: 0L) <= now }) {
            return@inTransaction false
        }
        val expires = Math.addExact(now, leaseMillis)
        attempt.heartbeatAt = now
        attempt.leaseExpiresAt = expires
        locks.forEach { it.leaseExpiresAt = expires }
        entityManager.flush()
        true
    }

    internal fun assertCurrent(token: QueueAttemptToken): QueueStatus = inTransaction {
        assertCurrentInTransaction(token)
    }

    internal fun <T> fencedDb(token: QueueAttemptToken, block: (EntityManager) -> T): T = inTransaction(fencedTransactions) {
        assertCurrentInTransaction(token)
        val result = block(entityManager)
        entityManager.flush()
        assertCurrentInTransaction(token)
        result
    }

    internal fun progress(token: QueueAttemptToken, snapshot: QueueProgress): Unit = inTransaction {
        val status = assertCurrentInTransaction(token)
        val now = clock.leaseNow()
        val job = entityManager.find(QueueJob::class.java, token.jobId, LockModeType.PESSIMISTIC_WRITE)
            ?: throw StaleAttempt()
        val attempt = entityManager.find(
            QueueAttempt::class.java, QueueAttemptId(token.jobId, token.attemptNo), LockModeType.PESSIMISTIC_WRITE,
        ) ?: throw StaleAttempt()
        if (job.status != status || !isCurrent(token, job, attempt, now)) throw StaleAttempt()
        applyProgress(job, attempt, snapshot)
        job.updatedAt = now
        bumpProjectionChange()
        entityManager.flush()
        assertCurrentInTransaction(token)
    }

    private fun applyProgress(job: QueueJob, attempt: QueueAttempt, snapshot: QueueProgress) {
        val json = progressMapper.writeValueAsString(snapshot.counters)
        job.progressStage = snapshot.stage
        job.progressJson = json
        attempt.progressStage = snapshot.stage
        attempt.progressJson = json
    }

    internal fun stagingDirectory(token: QueueAttemptToken): Path = stagingRoot
        .resolve(token.jobId.toString()).resolve("${token.attemptNo}-${token.fence}")
        .normalize().also { require(it.startsWith(stagingRoot)) }

    internal fun deleteStaging(token: QueueAttemptToken) {
        runCatching {
            val directory = stagingDirectory(token)
            deleteTree(directory)
            deleteEmptyDirectory(directory.parent)
        }
            .onFailure { logger.warn("Queue staging cleanup failed for job {} attempt {}", token.jobId, token.attemptNo, it) }
    }

    /** Job-row locking serializes pointer inspection with publication, including on other nodes. */
    internal fun cleanupOrphans(limit: Int = 100) {
        require(limit in 1..1000)
        val cursor = if (Files.exists(cleanupCursor)) Files.readString(cleanupCursor).toLongOrNull() ?: 0L else 0L
        val jobIds = java.util.TreeSet<Long>()
        for (base in listOf(stagingRoot, artifactRoot)) {
            Files.newDirectoryStream(base).use { directories ->
                for (directory in directories) {
                    val jobId = directory.fileName.toString().toLongOrNull() ?: continue
                    if (jobId > 0 && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) jobIds.add(jobId)
                }
            }
        }
        var remaining = limit
        var lastProcessed = cursor
        for (jobId in jobIds.tailSet(cursor, false)) {
            if (remaining == 0) {
                Files.writeString(cleanupCursor, lastProcessed.toString())
                return
            }
            val artifacts = artifactRoot.resolve(jobId.toString())
            if (cleanupStagingJobId != jobId && Files.isDirectory(artifacts, LinkOption.NOFOLLOW_LINKS)) {
                remaining--
                inTransaction {
                    entityManager.find(QueueJob::class.java, jobId, LockModeType.PESSIMISTIC_WRITE)
                    quarantineUnreferenced(artifacts, jobId)
                }
            }
            val staging = stagingRoot.resolve(jobId.toString())
            if (cleanupStagingJobId != jobId) {
                cleanupStagingJobId = jobId
                cleanupStagingAfter = null
            }
            if (Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.newDirectoryStream(staging).use { directories ->
                        for (directory in directories.sorted()) {
                            if (cleanupStagingAfter?.let { directory <= it } == true) continue
                            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue
                            val attempt = ATTEMPT_DIRECTORY.matchEntire(directory.fileName.toString()) ?: continue
                            val attemptNo = attempt.groupValues[1].toLongOrNull() ?: continue
                            val fence = attempt.groupValues[2].toLongOrNull() ?: continue
                            if (remaining == 0) {
                                Files.writeString(cleanupCursor, lastProcessed.toString())
                                return
                            }
                            remaining--
                            inTransaction {
                                entityManager.find(QueueJob::class.java, jobId, LockModeType.PESSIMISTIC_WRITE)
                                val row = entityManager.find(QueueAttempt::class.java, QueueAttemptId(jobId, attemptNo))
                                if (row == null || row.fence != fence || row.outcome != AttemptOutcome.RUNNING) deleteTree(directory)
                            }
                            cleanupStagingAfter = directory
                        }
                    }
                } finally {
                    deleteEmptyDirectory(staging)
                }
            }
            cleanupStagingJobId = 0L
            cleanupStagingAfter = null
            lastProcessed = jobId
        }
        Files.writeString(cleanupCursor, "0")
    }

    private fun quarantineUnreferenced(directory: Path, jobId: Long) {
        val referenced = entityManager.createQuery(
            "select a.storagePath from QueueArtifact a where a.job.id = :jobId",
            String::class.java,
        ).setParameter("jobId", jobId).resultList.toHashSet()
        val quarantine = root.resolve("artifacts-orphaned")
        Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (!attributes.isRegularFile) return FileVisitResult.CONTINUE
                val storagePath = root.relativize(file).toString().replace(java.io.File.separatorChar, '/')
                if (storagePath !in referenced) {
                    val target = quarantine.resolve(artifactRoot.relativize(file))
                    createDirectoriesWithoutLinks(root, target.parent)
                    Files.move(file, target, StandardCopyOption.ATOMIC_MOVE)
                }
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, failure: java.io.IOException?): FileVisitResult {
                if (failure != null) throw failure
                deleteEmptyDirectory(directory)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun deleteEmptyDirectory(directory: Path) {
        try {
            Files.deleteIfExists(directory)
        } catch (_: DirectoryNotEmptyException) {
            // Another attempt may have created staging before its parent could be removed.
        } catch (_: NoSuchFileException) {
            // A concurrent handler or cleanup pass already removed this directory.
        }
    }

    private fun deleteTree(directory: Path) {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return
        var parent = directory.parent
        while (parent != root) {
            require(parent != null && parent.startsWith(root) && !Files.isSymbolicLink(parent)) { "Unsafe queue cleanup path" }
            parent = parent.parent
        }
        Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, failure: java.io.IOException?): FileVisitResult {
                if (failure != null) throw failure
                Files.deleteIfExists(directory)
                return FileVisitResult.CONTINUE
            }
        })
    }

    internal fun complete(
        token: QueueAttemptToken,
        failure: Throwable?,
        artifacts: List<StagedQueueArtifact>,
        finalProgress: QueueProgress? = null,
    ): Boolean {
        // A commit error may follow a durable commit. Retain immutable bytes for pointer-based reconciliation.
        return inTransaction {
                assertCurrentInTransaction(token)
                if (token.stale.get()) throw StaleAttempt()
                val job = entityManager.find(QueueJob::class.java, token.jobId, LockModeType.PESSIMISTIC_WRITE)
                    ?: throw StaleAttempt()
                val attempt = entityManager.find(
                    QueueAttempt::class.java, QueueAttemptId(token.jobId, token.attemptNo), LockModeType.PESSIMISTIC_WRITE,
                ) ?: throw StaleAttempt()
                val now = clock.leaseNow()
                if (!isCurrent(token, job, attempt, now)) throw StaleAttempt()
                for ((key, resourceFence) in token.resourceFences) {
                    val lock = entityManager.find(QueueResourceLock::class.java, key, LockModeType.PESSIMISTIC_WRITE)
                        ?: throw StaleAttempt()
                    if (!owns(lock, token, resourceFence) || (lock.leaseExpiresAt ?: 0L) <= now) throw StaleAttempt()
                }

                val completion = classify(failure)
                val event = when (completion) {
                    QueueCompletion.Success -> QueueTransitionEvent.HandlerSucceeded
                    QueueCompletion.CooperativeCancel -> when {
                        job.status == QueueStatus.CANCEL_REQUESTED -> QueueTransitionEvent.CooperativeCancelCompleted
                        token.shutdownRequested.get() -> return@inTransaction false
                        else -> QueueTransitionEvent.UnclassifiedFailure
                    }
                    is QueueCompletion.Retryable -> QueueTransitionEvent.RetryableFailure
                    is QueueCompletion.Permanent -> QueueTransitionEvent.PermanentFailure
                    is QueueCompletion.RecoveryRequired -> QueueTransitionEvent.UnclassifiedFailure
                }
                val decision = QueueTransition.decide(
                    QueueTransitionInput(
                        stateOf(job, attempt.outcome), event,
                        QueueTransitionPolicy(token.definition.maxAttempts, true, token.definition.replaySafe),
                    ),
                )
                if (!decision.accepted) return@inTransaction false

                if (completion is QueueCompletion.Success) {
                    for (artifact in artifacts) {
                        val publishNow = clock.leaseNow()
                        if (!isCurrent(token, job, attempt, publishNow)) throw StaleAttempt()
                        for ((key, resourceFence) in token.resourceFences) {
                            val lock = entityManager.find(QueueResourceLock::class.java, key, LockModeType.PESSIMISTIC_WRITE)
                                ?: throw StaleAttempt()
                            if (!owns(lock, token, resourceFence) || (lock.leaseExpiresAt ?: 0L) <= publishNow) {
                                throw StaleAttempt()
                            }
                        }
                        val finalPath = publishPath(token, artifact.relativePath)
                        createDirectoriesWithoutLinks(artifactRoot, finalPath.parent)
                        Files.move(artifact.stagingPath, finalPath, StandardCopyOption.ATOMIC_MOVE)
                        val storagePath = root.relativize(finalPath).toString().replace(java.io.File.separatorChar, '/')
                        entityManager.persist(QueueArtifact(
                            artifactId = UUID.randomUUID().toString(), job = job,
                            executionGeneration = token.executionGeneration, attemptNo = token.attemptNo,
                            fence = token.fence, relativePath = artifact.relativePath, storagePath = storagePath,
                            sizeBytes = artifact.sizeBytes, sha256 = artifact.sha256,
                        ))
                    }
                    // A final DB-time/fence check immediately precedes the successful projection write.
                    if (!isCurrent(token, job, attempt, clock.leaseNow())) throw StaleAttempt()
                }

                val finalNow = clock.leaseNow()
                if (!isCurrent(token, job, attempt, finalNow)) throw StaleAttempt()
                for ((key, resourceFence) in token.resourceFences) {
                    val lock = entityManager.find(QueueResourceLock::class.java, key, LockModeType.PESSIMISTIC_WRITE)
                        ?: throw StaleAttempt()
                    if (!owns(lock, token, resourceFence) || (lock.leaseExpiresAt ?: 0L) <= finalNow) throw StaleAttempt()
                }
                finalProgress?.let { applyProgress(job, attempt, it) }
                attempt.outcome = decision.activeOutcome ?: throw IllegalStateException("Missing attempt outcome")
                attempt.finishedAt = finalNow
                when (completion) {
                    is QueueCompletion.Retryable -> {
                        setFailure(attempt, completion.code, completion.summary)
                        job.errorCode = completion.code
                        job.errorSummary = attempt.errorSummary
                    }
                    is QueueCompletion.Permanent -> {
                        setFailure(attempt, completion.code, completion.summary)
                        job.errorCode = completion.code
                        job.errorSummary = attempt.errorSummary
                    }
                    is QueueCompletion.RecoveryRequired -> {
                        setFailure(attempt, completion.code, completion.summary)
                        job.errorCode = completion.code
                        job.errorSummary = attempt.errorSummary
                    }
                    QueueCompletion.CooperativeCancel -> {
                        if (decision.activeOutcome == AttemptOutcome.CANCELLED) {
                            setFailure(attempt, "CANCELLED", "Task stopped at a cooperative cancellation checkpoint.")
                            job.errorCode = null
                            job.errorSummary = null
                        } else {
                            setFailure(attempt, "UNEXPECTED_CANCELLATION", "Task stopped without a queue cancellation request.")
                            job.errorCode = "UNEXPECTED_CANCELLATION"
                            job.errorSummary = attempt.errorSummary
                        }
                    }
                    QueueCompletion.Success -> {
                        job.errorCode = null
                        job.errorSummary = null
                    }
                }
                applyDecision(job, decision, finalNow)
                job.activeAttemptNo = null
                job.nextAttemptAt = if (job.status == QueueStatus.RETRY_WAIT) retryAt(job, finalNow) else null
                job.finishedAt = if (isTerminal(job.status)) finalNow else null
                bumpProjectionChange()
                entityManager.flush()
                if (attempt.leaseExpiresAt <= clock.leaseNow()) throw StaleAttempt()
                countAfterCommit(outcomeCounters.getValue(attempt.outcome))
                true
        }
    }

    private fun publishPath(token: QueueAttemptToken, relativePath: String): Path = artifactRoot
        .resolve(token.jobId.toString()).resolve("${token.attemptNo}-${token.fence}")
        .resolve(UUID.randomUUID().toString()).resolve(relativePath).normalize()
        .also { require(it.startsWith(artifactRoot)) { "Artifact path escaped queue data directory" } }

    private fun createDirectoriesWithoutLinks(safeRoot: Path, directory: Path) {
        require(Files.isDirectory(safeRoot, LinkOption.NOFOLLOW_LINKS)) { "Queue file root must not be a symlink" }
        val normalized = directory.toAbsolutePath().normalize()
        require(normalized.startsWith(safeRoot)) { "Artifact path escaped queue data directory" }
        var current = safeRoot
        for (part in safeRoot.relativize(normalized)) {
            current = current.resolve(part)
            try {
                Files.createDirectory(current)
            } catch (_: FileAlreadyExistsException) {
                // Existing components are accepted only after the no-symlink check below.
            }
            require(!Files.isSymbolicLink(current) && Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                "Artifact path contains a non-directory component"
            }
        }
    }


    internal fun recoverExpired(jobId: Long): Boolean = inTransaction {
        val job = entityManager.find(QueueJob::class.java, jobId, LockModeType.PESSIMISTIC_WRITE)
            ?: return@inTransaction false
        if (job.status != QueueStatus.RUNNING && job.status != QueueStatus.CANCEL_REQUESTED) return@inTransaction false
        val attemptNo = job.activeAttemptNo ?: return@inTransaction false
        val attempt = entityManager.find(
            QueueAttempt::class.java, QueueAttemptId(jobId, attemptNo), LockModeType.PESSIMISTIC_WRITE,
        ) ?: return@inTransaction false
        val now = clock.leaseNow()
        if (attempt.outcome != AttemptOutcome.RUNNING || attempt.leaseExpiresAt > now) return@inTransaction false
        val definition = registry.find(job.taskType, job.payloadVersion)
        val replaySafe = definition?.handler != null && definition.replaySafe
        val decision = QueueTransition.decide(
            QueueTransitionInput(
                stateOf(job, attempt.outcome), QueueTransitionEvent.LeaseExpired,
                QueueTransitionPolicy(definition?.maxAttempts ?: 5, definition?.handler != null, replaySafe),
            ),
        )
        if (!decision.accepted) return@inTransaction false
        attempt.outcome = decision.activeOutcome ?: AttemptOutcome.LEASE_LOST
        attempt.finishedAt = now
        setFailure(attempt, "LEASE_LOST", "Worker lease expired before completion was confirmed.")
        job.errorCode = "LEASE_LOST"
        job.errorSummary = attempt.errorSummary
        applyDecision(job, decision, now)
        job.activeAttemptNo = null
        job.nextAttemptAt = if (job.status == QueueStatus.RETRY_WAIT) retryAt(job, now) else null
        job.finishedAt = if (isTerminal(job.status)) now else null
        bumpProjectionChange()
        entityManager.flush()
        countAfterCommit(outcomeCounters.getValue(attempt.outcome))
        true
    }

    /** Called only after the old handler actually returned; conditional clear cannot erase a successor. */
    internal fun releaseAfterHandlerReturn(token: QueueAttemptToken) {
        inTransaction {
            for ((key, resourceFence) in token.resourceFences) {
                val lock = entityManager.find(QueueResourceLock::class.java, key, LockModeType.PESSIMISTIC_WRITE)
                    ?: continue
                if (owns(lock, token, resourceFence)) {
                    lock.currentJobId = null
                    lock.currentAttemptNo = null
                    lock.currentFence = null
                    lock.leaseExpiresAt = null
                }
            }
            entityManager.flush()
        }
    }

    internal fun bumpProjectionChange() {
        val updated = entityManager.createQuery(
            "update QueueCounter c set c.value = c.value + 1 where c.name = :name and c.value < :maximum",
        ).setParameter("name", "change-generation").setParameter("maximum", Long.MAX_VALUE).executeUpdate()
        check(updated == 1) { "VERSION_EXHAUSTED" }
    }

    private fun assertCurrentInTransaction(token: QueueAttemptToken): QueueStatus {
        if (token.stale.get()) throw StaleAttempt()
        val job = entityManager.find(QueueJob::class.java, token.jobId, LockModeType.PESSIMISTIC_WRITE)
            ?: throw StaleAttempt()
        val attempt = entityManager.find(
            QueueAttempt::class.java, QueueAttemptId(token.jobId, token.attemptNo), LockModeType.PESSIMISTIC_WRITE,
        ) ?: throw StaleAttempt()
        var resourceExpiry = Long.MAX_VALUE
        for ((key, resourceFence) in token.resourceFences) {
            val lock = entityManager.find(QueueResourceLock::class.java, key, LockModeType.PESSIMISTIC_WRITE)
                ?: throw StaleAttempt()
            if (!owns(lock, token, resourceFence)) throw StaleAttempt()
            resourceExpiry = minOf(resourceExpiry, lock.leaseExpiresAt ?: Long.MIN_VALUE)
        }
        val now = clock.leaseNow()
        if (!isCurrent(token, job, attempt, now) || resourceExpiry <= now) throw StaleAttempt()
        return job.status
    }

    private fun isCurrent(token: QueueAttemptToken, job: QueueJob, attempt: QueueAttempt, now: Long): Boolean =
        (job.status == QueueStatus.RUNNING || job.status == QueueStatus.CANCEL_REQUESTED) &&
            job.activeAttemptNo == token.attemptNo && job.executionGeneration == token.executionGeneration &&
            job.nextFence == token.fence && attempt.outcome == AttemptOutcome.RUNNING &&
            attempt.fence == token.fence && attempt.ownerInstance == token.ownerInstance &&
            attempt.leaseExpiresAt > now

    private fun owns(lock: QueueResourceLock, token: QueueAttemptToken, resourceFence: Long): Boolean =
        lock.currentJobId == token.jobId && lock.currentAttemptNo == token.attemptNo &&
            lock.currentFence == resourceFence && lock.fenceCounter == resourceFence

    private fun isDue(job: QueueJob, now: Long): Boolean = when (job.status) {
        QueueStatus.QUEUED -> job.scheduledAt <= now
        QueueStatus.RETRY_WAIT -> job.nextAttemptAt?.let { it <= now } == true
        else -> false
    }

    private fun stateOf(job: QueueJob, outcome: AttemptOutcome? = null) = QueueTransitionState(
        job.status, job.executionGeneration, job.generationAttemptNo, job.attemptCount,
        job.failureDisposition, outcome,
    )

    private fun applyDecision(job: QueueJob, decision: QueueTransitionDecision, now: Long) {
        Queue.updateFailedCount(entityManager, job.status, decision.status)
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
        job.finishedAt = if (isTerminal(decision.status)) now else null
    }

    private fun setFailure(attempt: QueueAttempt, code: String, summary: String) {
        attempt.errorCode = code.take(80)
        attempt.errorSummary = boundedSummary(summary)
    }

    private fun boundedSummary(value: String): String =
        if (value.length <= errorSummaryCodePoints || value.codePointCount(0, value.length) <= errorSummaryCodePoints) value
        else value.substring(0, value.offsetByCodePoints(0, errorSummaryCodePoints))

    private fun classify(failure: Throwable?): QueueCompletion = when (failure) {
        null -> QueueCompletion.Success
        is CooperativeTaskCancellation -> QueueCompletion.CooperativeCancel
        is RetryableTaskFailure -> QueueCompletion.Retryable(failure.code, failure.safeSummary)
        is PermanentTaskFailure -> QueueCompletion.Permanent(failure.code, failure.safeSummary)
        is RecoveryRequiredTaskFailure -> QueueCompletion.RecoveryRequired(failure.code, failure.safeSummary)
        is ClassifiedTaskFailure -> QueueCompletion.RecoveryRequired(failure.code, failure.safeSummary)
        else -> QueueCompletion.RecoveryRequired("UNEXPECTED_EXCEPTION", boundedSummary(failure.javaClass.simpleName))
    }

    private fun retryAt(job: QueueJob, now: Long): Long {
        val exponent = (job.generationAttemptNo - 1).coerceAtLeast(0).coerceAtMost(8)
        val ceiling = min(900_000L, 5_000L * (1L shl exponent))
        val floor = ceiling / 2
        val digest = MessageDigest.getInstance("SHA-256").digest(
            "${job.id}:${job.executionGeneration}:${job.generationAttemptNo}".toByteArray(Charsets.UTF_8),
        )
        val sample = ByteBuffer.wrap(digest).long and Long.MAX_VALUE
        val delay = floor + sample % (ceiling - floor + 1)
        return Math.addExact(now, delay)
    }

    private fun isTerminal(status: QueueStatus): Boolean = status in TERMINAL

    private fun countAfterCommit(counter: Counter) {
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = counter.increment()
        })
    }

    private fun <T> inTransaction(template: TransactionTemplate = transactions, block: () -> T): T {
        var value: Any? = null
        template.executeWithoutResult { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun Throwable.hasConstraintViolation(): Boolean = generateSequence(this) { it.cause }.any {
        it is ConstraintViolationException || it is DataIntegrityViolationException
    }

    companion object {
        private val logger = LoggerFactory.getLogger(QueueWorkerStore::class.java)
        private val ATTEMPT_DIRECTORY = Regex("([1-9][0-9]*)-([1-9][0-9]*)")
        private val TERMINAL = setOf(
            QueueStatus.SUCCEEDED, QueueStatus.FAILED, QueueStatus.RECOVERY_REQUIRED,
            QueueStatus.BLOCKED_UNSUPPORTED, QueueStatus.CANCELLED,
        )
    }

    private sealed interface QueueCompletion {
        data object Success : QueueCompletion
        data object CooperativeCancel : QueueCompletion
        data class Retryable(val code: String, val summary: String) : QueueCompletion
        data class Permanent(val code: String, val summary: String) : QueueCompletion
        data class RecoveryRequired(val code: String, val summary: String) : QueueCompletion
    }
}
