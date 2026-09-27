package com.github.yonaprojects.yona.queue.acceptance

import com.github.yonaprojects.yona.queue.Queue
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant
import javax.sql.DataSource

data class QueueJobRow(
    val id: Long,
    val status: String,
    val payloadVersion: Int,
    val executionGeneration: Long,
    val generationAttemptNo: Int,
    val attemptCount: Long,
    val failureDisposition: String? = null,
)

data class QueueAttemptRow(
    val attemptNo: Long,
    val executionGeneration: Long,
    val generationAttemptNo: Int,
    val status: String,
    val fence: Long,
)

interface RealQueueAcceptanceFixture : AutoCloseable {
    val dataSource: DataSource
    val transactionManager: PlatformTransactionManager
    val queue: Queue
    fun createAcceptanceMarkerTable()
    fun seedExistingYonaRowsThroughProductionServices()
    fun registerStoreAcceptanceTasks()
    fun jobRow(jobId: Long): QueueJobRow?
    fun attemptRows(jobId: Long): List<QueueAttemptRow>
    fun idempotencyRowCount(jobId: Long): Int
    fun snapshotUnrelatedYonaRows(): Map<String, String>
    fun applyQueueOnlyUpgradeToExistingYonaSchema()
    fun reopenOnSamePersistentDatabase(): RealQueueAcceptanceFixture
}

enum class FencedActionOutcome { ACCEPTED, REJECTED_STALE_OWNER }

data class FencedActionObservation(
    val requestId: String,
    val workerInstanceId: String,
    val jobId: Long,
    val attempted: Boolean,
    val fence: Long,
    val outcome: FencedActionOutcome,
)

data class PersistedFenceMutation(
    val requestId: String,
    val jobId: Long,
    val fence: Long,
    val value: String,
)

data class PublishedQueueArtifact(
    val relativePath: String,
    val jobId: Long,
    val executionGeneration: Long,
    val attemptNo: Long,
    val fence: Long,
    val bytes: ByteArray,
)

/** Real separate-JVM worker acceptance; no admin HTTP or mocked queue effects. */
interface NoHttpWorkerAcceptanceFixture : RealQueueAcceptanceFixture {
    fun registerWorkerAcceptanceTasks()
    fun enqueueWorkerScenario(
        scenario: String,
        resourceKey: String,
        payload: ByteArray = byteArrayOf(),
        publicationPath: String? = null,
        dueAt: Instant? = null,
    ): Long
    fun startRealWorkerProcess(
        instanceId: String, shutdownGraceMillis: Long = 30_000, heartbeatMillis: Long = 15_000, workers: Int = 1,
    ): QueueWorkerProcess
    fun awaitHandlerGate(jobId: Long, timeoutMillis: Long)
    fun releaseHandlerGate(jobId: Long)
    fun awaitHandlerReturned(workerInstanceId: String, jobId: Long, timeoutMillis: Long)
    fun seededSiteAdminActorId(): Long
    fun cancelDirectly(jobId: Long, commandId: String, actorId: Long)
    fun awaitJobStatusFromSql(jobId: Long, status: String, timeoutMillis: Long): QueueJobRow
    fun awaitClaimBlockedByResource(jobId: Long, workerCount: Int, timeoutMillis: Long)
    fun maxConcurrentEffects(resourceKey: String): Int
    fun taskStartCount(jobId: Long): Int
    fun effectCount(runId: String): Int
    fun nextAttemptAt(jobId: Long): Long?
    fun retryDelayMillis(jobId: Long): Long
    fun databaseClaimContenderCount(jobId: Long): Long
    fun databaseNow(): Long
    fun advanceTestDatabaseClockBy(millis: Long)
    fun retardTestDatabaseClockBy(millis: Long)
    fun requestFencedDatabaseMutation(
        workerInstanceId: String,
        jobId: Long,
        requestId: String,
        value: String,
    ): FencedActionObservation
    fun committedFenceMutation(requestId: String): PersistedFenceMutation?
    fun requestFencedFilePublication(
        workerInstanceId: String,
        jobId: Long,
        requestId: String,
        relativePath: String,
        bytes: ByteArray,
    ): FencedActionObservation
    fun visiblePublishedArtifact(relativePath: String): PublishedQueueArtifact?
}

interface QueueWorkerProcess : AutoCloseable {
    val instanceId: String
    fun awaitReady(timeoutMillis: Long)
    fun stopClaimingNewWork()
    fun killForcibly()
    fun beginGracefulShutdown()
    fun awaitStopped(timeoutMillis: Long)
}
