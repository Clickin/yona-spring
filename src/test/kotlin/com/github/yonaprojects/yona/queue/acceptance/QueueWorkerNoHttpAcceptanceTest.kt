package com.github.yonaprojects.yona.queue.acceptance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import java.time.Instant
import java.util.UUID

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [QueueAcceptanceFixtureTestConfiguration::class])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class QueueWorkerNoHttpAcceptanceTest @Autowired constructor(
    @Qualifier("noHttpWorkerAcceptanceFixture") private val fixture: NoHttpWorkerAcceptanceFixture,
) {
    @Test
    fun twoRealWorkerProcessesHaveOneClaimAndPersistRetryClassification() {
        fixture.registerWorkerAcceptanceTasks()
        val first = fixture.startRealWorkerProcess("node-a-${UUID.randomUUID()}")
        val second = fixture.startRealWorkerProcess("node-b-${UUID.randomUUID()}")
        try {
            first.awaitReady(30_000)
            second.awaitReady(30_000)
            val gated = fixture.enqueueWorkerScenario("simultaneous-claim", resourceKey("claim"))
            fixture.awaitHandlerGate(gated, 30_000)
            assertEquals("RUNNING", fixture.jobRow(gated)?.status)
            assertEquals(1, fixture.attemptRows(gated).size)
            assertEquals(1, fixture.taskStartCount(gated))
            fixture.releaseHandlerGate(gated)
            fixture.awaitJobStatusFromSql(gated, "SUCCEEDED", 30_000)
            assertEquals(1, fixture.attemptRows(gated).size)
            assertEquals(1, fixture.taskStartCount(gated))

            val retry = fixture.enqueueWorkerScenario("retryable-then-success", resourceKey("retry"))
            fixture.awaitJobStatusFromSql(retry, "SUCCEEDED", 60_000)
            assertEquals(listOf("RETRYABLE_FAILURE", "SUCCEEDED"), fixture.attemptRows(retry).map { it.status })

            val permanent = fixture.enqueueWorkerScenario("permanent-failure", resourceKey("permanent"))
            fixture.awaitJobStatusFromSql(permanent, "FAILED", 30_000)
            fixture.advanceTestDatabaseClockBy(30 * 60 * 1000L)
            assertEquals("FAILED", fixture.jobRow(permanent)?.status)
            assertEquals(1, fixture.attemptRows(permanent).size)

            val unsupported = fixture.enqueueWorkerScenario("unsupported-payload-version", resourceKey("unsupported"))
            fixture.awaitJobStatusFromSql(unsupported, "BLOCKED_UNSUPPORTED", 30_000)
            assertEquals(0, fixture.attemptRows(unsupported).size)
        } finally {
            second.close()
            first.close()
        }
    }

    @Test
    fun cancelKeepsTheSharedResourceUntilTheRunningHandlerActuallyReturns() {
        fixture.registerWorkerAcceptanceTasks()
        val first = fixture.startRealWorkerProcess("cancel-a-${UUID.randomUUID()}")
        val second = fixture.startRealWorkerProcess("cancel-b-${UUID.randomUUID()}")
        try {
            first.awaitReady(30_000)
            second.awaitReady(30_000)
            val resource = resourceKey("shared")
            val running = fixture.enqueueWorkerScenario("gated-cancel", resource)
            fixture.awaitHandlerGate(running, 30_000)
            fixture.cancelDirectly(running, UUID.randomUUID().toString(), actorId = fixture.seededSiteAdminActorId())
            assertEquals("CANCEL_REQUESTED", fixture.jobRow(running)?.status)

            val successor = fixture.enqueueWorkerScenario("success", resource)
            fixture.awaitClaimBlockedByResource(successor, workerCount = 2, timeoutMillis = 10_000)
            assertEquals(0, fixture.taskStartCount(successor))
            assertTrue(fixture.attemptRows(successor).isEmpty())
            fixture.releaseHandlerGate(running)
            fixture.awaitJobStatusFromSql(running, "CANCELLED", 30_000)
            fixture.awaitJobStatusFromSql(successor, "SUCCEEDED", 30_000)
            assertEquals(1, fixture.taskStartCount(successor))
            assertEquals(1, fixture.maxConcurrentEffects(resource))
        } finally {
            second.close()
            first.close()
        }
    }

    @Test
    fun cancelRequestedLeaseExpiryRequiresRecoveryAndKeepsResourceUntilHandlerReturns() {
        fixture.registerWorkerAcceptanceTasks()
        val old = fixture.startRealWorkerProcess("cancel-expired-old-${UUID.randomUUID()}")
        var successor: QueueWorkerProcess? = null
        try {
            old.awaitReady(30_000)
            val resource = resourceKey("cancel-expired")
            val cancelled = fixture.enqueueWorkerScenario("unsafe-gated-cancel", resource)
            fixture.awaitHandlerGate(cancelled, 30_000)
            assertOwner(cancelled, old.instanceId)
            successor = fixture.startRealWorkerProcess("cancel-expired-next-${UUID.randomUUID()}")
            successor.awaitReady(30_000)
            fixture.cancelDirectly(cancelled, UUID.randomUUID().toString(), actorId = fixture.seededSiteAdminActorId())
            assertEquals("CANCEL_REQUESTED", fixture.jobRow(cancelled)?.status)

            fixture.advanceTestDatabaseClockBy(120_000)
            fixture.awaitJobStatusFromSql(cancelled, "RECOVERY_REQUIRED", 30_000)
            assertEquals(listOf("LEASE_LOST"), fixture.attemptRows(cancelled).map { it.status })
            assertEquals(1, fixture.taskStartCount(cancelled))

            val successorJob = fixture.enqueueWorkerScenario("success", resource)
            fixture.awaitClaimBlockedByResource(successorJob, workerCount = 2, timeoutMillis = 10_000)
            assertEquals("QUEUED", fixture.jobRow(successorJob)?.status)
            assertTrue(fixture.attemptRows(successorJob).isEmpty())
            assertEquals(0, fixture.taskStartCount(successorJob))

            fixture.releaseHandlerGate(cancelled)
            fixture.awaitHandlerReturned(old.instanceId, cancelled, 30_000)
            assertEquals("RECOVERY_REQUIRED", fixture.jobRow(cancelled)?.status)
            assertEquals(listOf("LEASE_LOST"), fixture.attemptRows(cancelled).map { it.status })
            fixture.awaitJobStatusFromSql(successorJob, "SUCCEEDED", 30_000)
            assertEquals(1, fixture.taskStartCount(successorJob))
            assertEquals(1, fixture.maxConcurrentEffects(resource))
        } finally {
            successor?.close()
            old.close()
        }
    }

    @Test
    fun staleOwnerDatabaseAndPublicationWritesAreRejectedUntilSuccessorOwnsFinalPublish() {
        fixture.registerWorkerAcceptanceTasks()
        val old = fixture.startRealWorkerProcess("fence-old-${UUID.randomUUID()}")
        var successor: QueueWorkerProcess? = null
        try {
            old.awaitReady(30_000)
            val finalPath = "queue-acceptance/${UUID.randomUUID()}.bin"
            val resource = resourceKey("artifact")
            val staleJob = fixture.enqueueWorkerScenario("stale-fence-file", resource, publicationPath = finalPath)
            fixture.awaitHandlerGate(staleJob, 30_000)
            assertOwner(staleJob, old.instanceId)
            successor = fixture.startRealWorkerProcess("fence-new-${UUID.randomUUID()}")
            successor.awaitReady(30_000)
            val oldAttempt = fixture.attemptRows(staleJob).single()

            fixture.advanceTestDatabaseClockBy(120_000)
            fixture.awaitJobStatusFromSql(staleJob, "RECOVERY_REQUIRED", 30_000)
            assertEquals(listOf("LEASE_LOST"), fixture.attemptRows(staleJob).map { it.status })
            assertEquals(1, fixture.taskStartCount(staleJob))

            val dbRequestId = UUID.randomUUID().toString()
            val dbObservation = fixture.requestFencedDatabaseMutation(
                old.instanceId, staleJob, dbRequestId, "stale-value-$dbRequestId",
            )
            assertEquals(dbRequestId, dbObservation.requestId)
            assertTrue(dbObservation.attempted)
            assertEquals(old.instanceId, dbObservation.workerInstanceId)
            assertEquals(staleJob, dbObservation.jobId)
            assertEquals(oldAttempt.fence, dbObservation.fence)
            assertEquals(FencedActionOutcome.REJECTED_STALE_OWNER, dbObservation.outcome)
            assertNull(fixture.committedFenceMutation(dbRequestId))

            val publicationRequestId = UUID.randomUUID().toString()
            val staleBytes = "stale-final-publication-$publicationRequestId".toByteArray()
            val publicationObservation = fixture.requestFencedFilePublication(
                old.instanceId, staleJob, publicationRequestId, finalPath, staleBytes,
            )
            assertEquals(publicationRequestId, publicationObservation.requestId)
            assertTrue(publicationObservation.attempted)
            assertEquals(old.instanceId, publicationObservation.workerInstanceId)
            assertEquals(staleJob, publicationObservation.jobId)
            assertEquals(oldAttempt.fence, publicationObservation.fence)
            assertEquals(FencedActionOutcome.REJECTED_STALE_OWNER, publicationObservation.outcome)
            assertNull(fixture.visiblePublishedArtifact(finalPath))
            val successorBytes = "successor-final-publication-${UUID.randomUUID()}".toByteArray()

            val successorJob = fixture.enqueueWorkerScenario(
                "success-publish-file", resource, successorBytes, publicationPath = finalPath,
            )
            fixture.awaitClaimBlockedByResource(successorJob, workerCount = 2, timeoutMillis = 10_000)
            assertEquals("QUEUED", fixture.jobRow(successorJob)?.status)
            assertTrue(fixture.attemptRows(successorJob).isEmpty())
            assertEquals(0, fixture.taskStartCount(successorJob))

            old.stopClaimingNewWork()
            fixture.releaseHandlerGate(staleJob)
            fixture.awaitHandlerReturned(old.instanceId, staleJob, 30_000)
            assertEquals("RECOVERY_REQUIRED", fixture.jobRow(staleJob)?.status)
            assertEquals(listOf("LEASE_LOST"), fixture.attemptRows(staleJob).map { it.status })

            fixture.awaitJobStatusFromSql(successorJob, "SUCCEEDED", 60_000)
            val published = fixture.visiblePublishedArtifact(finalPath)
            assertEquals(successorJob, published?.jobId)
            assertEquals(finalPath, published?.relativePath)
            assertEquals(successorBytes.toList(), published?.bytes?.toList())
            assertEquals(fixture.attemptRows(successorJob).single().fence, published?.fence)
            assertEquals(fixture.attemptRows(successorJob).single().executionGeneration, published?.executionGeneration)
            assertEquals(fixture.attemptRows(successorJob).single().attemptNo, published?.attemptNo)
            assertEquals(listOf("SUCCEEDED"), fixture.attemptRows(successorJob).map { it.status })
            assertEquals(1, fixture.taskStartCount(successorJob))
            assertEquals(1, fixture.maxConcurrentEffects(resource))
        } finally {
            successor?.close()
            old.close()
        }
    }

    @Test
    fun restartOnSameDatabaseReplaysSafeWorkAndBlocksUnsafeWork() {
        fixture.registerWorkerAcceptanceTasks()
        val old = fixture.startRealWorkerProcess("restart-old-${UUID.randomUUID()}")
        old.awaitReady(30_000)
        val safe = fixture.enqueueWorkerScenario("process-crash-replay", resourceKey("restart-safe"))
        fixture.awaitHandlerGate(safe, 30_000)
        old.killForcibly()

        fixture.advanceTestDatabaseClockBy(120_000)
        val replacement = fixture.startRealWorkerProcess("restart-new-${UUID.randomUUID()}")
        try {
            replacement.awaitReady(30_000)
            fixture.awaitJobStatusFromSql(safe, "SUCCEEDED", 60_000)
            assertEquals(listOf("LEASE_LOST", "SUCCEEDED"), fixture.attemptRows(safe).map { it.status })
            assertEquals(1, fixture.effectCount(safe.toString()))

            val unsafe = fixture.enqueueWorkerScenario("process-crash-unsafe", resourceKey("restart-unsafe"))
            fixture.awaitHandlerGate(unsafe, 30_000)
            replacement.killForcibly()
            fixture.advanceTestDatabaseClockBy(120_000)
            val recoveryWorker = fixture.startRealWorkerProcess("restart-recovery-${UUID.randomUUID()}")
            try {
                recoveryWorker.awaitReady(30_000)
                fixture.awaitJobStatusFromSql(unsafe, "RECOVERY_REQUIRED", 60_000)
                assertEquals(1, fixture.attemptRows(unsafe).size)
                assertEquals(1, fixture.effectCount(unsafe.toString()))
            } finally {
                recoveryWorker.close()
            }
        } finally {
            replacement.close()
        }
    }

    @Test
    fun retryBudgetExhaustionPersistsExactlyFiveAttempts() {
        fixture.registerWorkerAcceptanceTasks()
        val worker = fixture.startRealWorkerProcess("retry-budget-${UUID.randomUUID()}")
        try {
            worker.awaitReady(30_000)
            val job = fixture.enqueueWorkerScenario("retry-budget-exhaustion", resourceKey("retry-budget"))
            for (attemptNo in 1..5) {
                awaitAttemptCount(job, attemptNo, 30_000)
                val expectedStatus = if (attemptNo == 5) "FAILED" else "RETRY_WAIT"
                fixture.awaitJobStatusFromSql(job, expectedStatus, 30_000)
                assertEquals(attemptNo, fixture.attemptRows(job).size)
                if (attemptNo < 5) {
                    val ceiling = minOf(900_000L, 5_000L * (1L shl (attemptNo - 1)))
                    assertTrue(fixture.retryDelayMillis(job) in ceiling / 2..ceiling)
                }
                if (attemptNo == 1) {
                    val nextAttemptAt = fixture.nextAttemptAt(job) ?: error("Retry deadline was not persisted")
                    assertTrue(nextAttemptAt > fixture.databaseNow())
                    fixture.retardTestDatabaseClockBy(60_000)
                    Thread.sleep(1_500)
                    assertEquals(1, fixture.attemptRows(job).size)
                    assertEquals(1, fixture.taskStartCount(job))
                    fixture.advanceTestDatabaseClockBy(90 * 60 * 1000L)
                } else if (attemptNo < 5) {
                    fixture.advanceTestDatabaseClockBy(30 * 60 * 1000L)
                }
            }
            assertEquals("RETRY_EXHAUSTED", fixture.jobRow(job)?.failureDisposition)
            assertEquals(List(5) { "RETRYABLE_FAILURE" }, fixture.attemptRows(job).map { it.status })
            assertEquals(5, fixture.taskStartCount(job))
        } finally {
            worker.close()
        }
    }

    @Test
    fun futureScheduledJobDoesNotStartBeforeItsDatabaseDueTime() {
        fixture.registerWorkerAcceptanceTasks()
        val worker = fixture.startRealWorkerProcess("not-before-${UUID.randomUUID()}")
        try {
            worker.awaitReady(30_000)
            val dueAt = Instant.ofEpochMilli(fixture.databaseNow() + 30_000)
            val job = fixture.enqueueWorkerScenario("success", resourceKey("not-before"), dueAt = dueAt)
            Thread.sleep(1_500)
            assertEquals("QUEUED", fixture.jobRow(job)?.status)
            assertTrue(fixture.attemptRows(job).isEmpty())
            assertEquals(0, fixture.taskStartCount(job))

            fixture.advanceTestDatabaseClockBy(60_000)
            fixture.awaitJobStatusFromSql(job, "SUCCEEDED", 30_000)
            assertEquals(1, fixture.attemptRows(job).size)
            assertEquals(1, fixture.taskStartCount(job))
        } finally {
            worker.close()
        }
    }

    private fun awaitAttemptCount(jobId: Long, count: Int, timeoutMillis: Long) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (fixture.attemptRows(jobId).size >= count) return
            Thread.sleep(20)
        }
        error("Timed out waiting for $count durable attempts on job $jobId")
    }

    private fun assertOwner(jobId: Long, instanceId: String) {
        assertEquals(instanceId, JdbcTemplate(fixture.dataSource).queryForObject(
            "SELECT owner_instance FROM queue_attempt WHERE job_id = ?", String::class.java, jobId,
        ))
    }

    private fun resourceKey(kind: String): String =
        "$kind:${(UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1)}"
}
