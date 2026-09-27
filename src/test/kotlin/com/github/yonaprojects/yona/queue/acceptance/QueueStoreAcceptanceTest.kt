// No MockMvc/admin HTTP and no mocked DataSource/repository are used.
package com.github.yonaprojects.yona.queue.acceptance

import com.github.yonaprojects.yona.queue.ProvisionalQueueReceipt
import com.github.yonaprojects.yona.queue.QueueAdmissionException
import com.github.yonaprojects.yona.queue.QueueReceiptCommitState
import com.github.yonaprojects.yona.queue.QueueStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [QueueAcceptanceFixtureTestConfiguration::class])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class QueueStoreAcceptanceTest @Autowired constructor(
    @Qualifier("realQueueAcceptanceFixture") private val fixture: RealQueueAcceptanceFixture,
) {
    private val jdbc by lazy { JdbcTemplate(fixture.dataSource) }
    private val dueAt = Instant.parse("2100-01-01T00:00:00Z")
    private val taskType = "queue.acceptance.store.v1"
    private val scope = "queue-store-acceptance"

    @Test
    fun businessWriteAndQueueAdmissionRollbackAfterProvisionalReceiptWasReturned() {
        fixture.registerStoreAcceptanceTasks()
        fixture.createAcceptanceMarkerTable()
        val markerId = UUID.randomUUID().toString()
        var observedInsideTransaction: ProvisionalQueueReceipt? = null

        assertThrows(RollbackAfterReceipt::class.java) {
            TransactionTemplate(fixture.transactionManager).executeWithoutResult {
                jdbc.update("INSERT INTO queue_acceptance_marker(marker_id) VALUES (?)", markerId)
                observedInsideTransaction = fixture.queue.enqueue(
                    taskType, 1, "{\"marker\":\"rollback\"}".toByteArray(), dueAt, null, scope,
                )
                assertEquals(QueueReceiptCommitState.PROVISIONAL, observedInsideTransaction!!.commitState)
                throw RollbackAfterReceipt()
            }
        }

        val provisional = observedInsideTransaction ?: error("Queue.enqueue must return before outer commit")
        assertEquals(QueueReceiptCommitState.PROVISIONAL, provisional.commitState)
        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM queue_acceptance_marker WHERE marker_id = ?", Int::class.java, markerId,
        ))
        assertNull(fixture.jobRow(provisional.jobId))
    }

    @Test
    fun committedOuterTransactionLeavesQueueAndBusinessRowsDurable() {
        fixture.registerStoreAcceptanceTasks()
        fixture.createAcceptanceMarkerTable()
        val markerId = UUID.randomUUID().toString()
        val receipt = TransactionTemplate(fixture.transactionManager).execute {
            jdbc.update("INSERT INTO queue_acceptance_marker(marker_id) VALUES (?)", markerId)
            val inside = fixture.queue.enqueue(
                taskType, 1, "{\"marker\":\"commit\"}".toByteArray(), dueAt, null, scope,
            )
            assertEquals(QueueReceiptCommitState.PROVISIONAL, inside.commitState)
            inside
        }!!

        assertEquals(QueueReceiptCommitState.PROVISIONAL, receipt.commitState)
        assertNotNull(fixture.jobRow(receipt.jobId))
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM queue_acceptance_marker WHERE marker_id = ?", Int::class.java, markerId,
        ))
    }

    @Test
    fun exactScopedKeyUsesBytesAndVersionWhileUnkeyedJobsNeverShareAUniqueNullTuple() {
        fixture.registerStoreAcceptanceTasks()
        val key = "same-key-${UUID.randomUUID()}"
        val exact = "{\"left\":1,\"right\":\"x\"}".toByteArray()
        val reordered = "{ \"right\" : \"x\", \"left\" : 1 }".toByteArray()
        val first = admit(taskType, 1, exact, key, scope)
        val replay = admit(taskType, 1, exact, key, scope)
        assertEquals(first.jobId, replay.jobId)
        assertEquals(1, fixture.idempotencyRowCount(first.jobId))

        val byteConflict = assertThrows(QueueAdmissionException::class.java) {
            admit(taskType, 1, reordered, key, scope)
        }
        assertEquals("IDEMPOTENCY_CONFLICT", byteConflict.code)
        val versionConflict = assertThrows(QueueAdmissionException::class.java) {
            admit(taskType, 2, exact, key, scope)
        }
        assertEquals("IDEMPOTENCY_CONFLICT", versionConflict.code)

        val otherScope = admit(taskType, 1, exact, key, "$scope-other")
        val otherType = admit("queue.acceptance.other.v1", 1, exact, key, scope)
        assertNotEquals(first.jobId, otherScope.jobId)
        assertNotEquals(first.jobId, otherType.jobId)

        val unkeyedA = admit(taskType, 1, exact, null, scope)
        val unkeyedB = admit(taskType, 1, exact, null, scope)
        assertNotEquals(unkeyedA.jobId, unkeyedB.jobId)
        assertEquals(0, fixture.idempotencyRowCount(unkeyedA.jobId))
        assertEquals(0, fixture.idempotencyRowCount(unkeyedB.jobId))
    }

    @Test
    fun committedJobsSurviveStoreContextReopenAndQueueUpgradePreservesYonaRows() {
        fixture.registerStoreAcceptanceTasks()
        fixture.seedExistingYonaRowsThroughProductionServices()
        val before = fixture.snapshotUnrelatedYonaRows()
        fixture.applyQueueOnlyUpgradeToExistingYonaSchema()
        assertEquals(before, fixture.snapshotUnrelatedYonaRows())

        val receipt = admit(taskType, 1, "{\"marker\":\"reopen\"}".toByteArray(), null, scope)
        val beforeReopenJob = fixture.jobRow(receipt.jobId)
        assertNotNull(beforeReopenJob)
        val beforeReopenAttempts = fixture.attemptRows(receipt.jobId)
        assertEquals(emptyList<QueueAttemptRow>(), beforeReopenAttempts)

        val reopened = fixture.reopenOnSamePersistentDatabase()
        try {
            assertEquals(beforeReopenJob, reopened.jobRow(receipt.jobId))
            assertEquals(beforeReopenAttempts, reopened.attemptRows(receipt.jobId))
        } finally {
            reopened.close()
        }
    }

    @Test
    fun concurrentAdmissionsCoalesceKeysButPreserveEveryUnkeyedRequest() {
        fixture.registerStoreAcceptanceTasks()
        val pool = fixture.dataSource.unwrap(com.zaxxer.hikari.HikariDataSource::class.java)
        val originalPoolSize = pool.maximumPoolSize
        // Four business transactions plus the independent admission read and clock-health reader.
        pool.maximumPoolSize = 6
        val workers = Executors.newFixedThreadPool(4)
        try {
            val key = "racing-${UUID.randomUUID()}"
            val gate = CountDownLatch(1)
            val keyed = (1..4).map {
                workers.submit<Long> {
                    check(gate.await(10, TimeUnit.SECONDS))
                    admit(taskType, 1, "{\"marker\":\"race\"}".toByteArray(), key, scope).jobId
                }
            }
            gate.countDown()
            val ids = keyed.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(setOf(ids.first()), ids.toSet())
            assertEquals(1, fixture.idempotencyRowCount(ids.first()))
            val unkeyed = (1..4).map {
                workers.submit<Long> { admit(taskType, 1, "{}".toByteArray(), null, scope).jobId }
            }.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(4, unkeyed.toSet().size)
            unkeyed.forEach { assertEquals(0, fixture.idempotencyRowCount(it)) }
        } finally {
            try {
                workers.shutdownNow()
                check(workers.awaitTermination(10, TimeUnit.SECONDS))
            } finally {
                pool.maximumPoolSize = originalPoolSize
            }
        }
    }

    @Test
    fun businessSnapshotCanObserveAKeyCommittedByAnotherTransaction() {
        fixture.registerStoreAcceptanceTasks()
        val worker = Executors.newSingleThreadExecutor()
        val snapshotTaken = CountDownLatch(1)
        val winnerCommitted = CountDownLatch(1)
        val key = "snapshot-${UUID.randomUUID()}"
        try {
            val follower = worker.submit<Long> {
                TransactionTemplate(fixture.transactionManager).execute {
                    jdbc.queryForObject("SELECT COUNT(*) FROM queue_job", Long::class.java)
                    snapshotTaken.countDown()
                    check(winnerCommitted.await(15, TimeUnit.SECONDS))
                    fixture.queue.enqueue(taskType, 1, "{}".toByteArray(), dueAt, key, scope).jobId
                }!!
            }
            check(snapshotTaken.await(15, TimeUnit.SECONDS))
            val winner = admit(taskType, 1, "{}".toByteArray(), key, scope)
            winnerCommitted.countDown()
            assertEquals(winner.jobId, follower.get(30, TimeUnit.SECONDS))
        } finally {
            winnerCommitted.countDown()
            worker.shutdownNow()
            check(worker.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun admissionOwnsItsBytesAndUnknownVersionsRemainDurablyQueryable() {
        fixture.registerStoreAcceptanceTasks()
        val bytes = "{\"marker\":\"original\"}".toByteArray()
        val key = "owned-${UUID.randomUUID()}"
        val first = TransactionTemplate(fixture.transactionManager).execute {
            val receipt = fixture.queue.enqueue(taskType, 1, bytes, dueAt, key, scope)
            bytes.fill(0)
            receipt
        }!!
        assertEquals(first.jobId, admit(taskType, 1, "{\"marker\":\"original\"}".toByteArray(), key, scope).jobId)

        val unknown = admit("future.task", 37, byteArrayOf(0, -1, 2), key, scope)
        val projection = checkNotNull(fixture.queue.find(unknown.jobId))
        assertEquals("future.task", projection.taskType)
        assertEquals(37, projection.payloadVersion)
        assertEquals(QueueStatus.QUEUED, projection.status)
        assertEquals(unknown.jobId, admit("future.task", 37, byteArrayOf(0, -1, 2), key, scope).jobId)
        assertEquals(emptyList<QueueAttemptRow>(), fixture.attemptRows(unknown.jobId))
    }

    @Test
    fun registeredPayloadValidationRejectsAmbiguousOrMalformedInput() {
        fixture.registerStoreAcceptanceTasks()
        val invalid = listOf(
            byteArrayOf(-1),
            byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "{}".toByteArray(),
            "{}".toByteArray(Charsets.UTF_16BE),
            byteArrayOf(0, 0, 0, 123, 0, 0, 0, 125),
            "{\"marker\":\"".toByteArray() + byteArrayOf(0xc0.toByte(), 0xaf.toByte()) + "\"}".toByteArray(),
            "{\"marker\":\"a\",\"marker\":\"b\"}".toByteArray(),
            "{\"unknown\":true}".toByteArray(),
            "{} {}".toByteArray(),
            "{\"left\":NaN}".toByteArray(),
        )
        invalid.forEach { payload ->
            val error = assertThrows(QueueAdmissionException::class.java) { admit(taskType, 1, payload, null, scope) }
            assertEquals("INVALID_PAYLOAD", error.code)
        }
        val oversized = assertThrows(QueueAdmissionException::class.java) {
            admit("future.task", 1, ByteArray(1_048_577), null, scope)
        }
        assertEquals("INVALID_REQUEST", oversized.code)
    }

    @Test
    fun fractionalNotBeforeInstantsRoundUpToTheNextStoredMillisecond() {
        fixture.registerStoreAcceptanceTasks()
        val receipt = TransactionTemplate(fixture.transactionManager).execute {
            fixture.queue.enqueue(
                taskType, 1, "{}".toByteArray(), Instant.parse("2100-01-01T00:00:00.000001Z"), null, scope,
            )
        }!!
        assertEquals(Instant.parse("2100-01-01T00:00:00.001Z"), receipt.scheduledAt)
        assertEquals(4102444800001L, fixture.queue.find(receipt.jobId)!!.scheduledAt)
    }

    @Test
    fun repeatedResourceRequirementsRemainOneIdentityPerJob() {
        fixture.registerStoreAcceptanceTasks()
        val receipt = admit("queue.acceptance.resources.v1", 1, "{}".toByteArray(), null, scope)
        val resources = jdbc.queryForList(
            "SELECT resource_key FROM queue_job_resource WHERE job_id = ? ORDER BY resource_key",
            String::class.java, receipt.jobId,
        )
        assertEquals(listOf("repo:2", "repo:7"), resources)
    }

    private fun admit(
        type: String,
        version: Int,
        payload: ByteArray,
        key: String?,
        callerScope: String,
    ): ProvisionalQueueReceipt = TransactionTemplate(fixture.transactionManager).execute {
        fixture.queue.enqueue(type, version, payload, dueAt, key, callerScope)
    }!!

    private class RollbackAfterReceipt : RuntimeException()
}
