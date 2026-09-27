package com.github.yonaprojects.yona.queue

import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserState
import com.github.yonaprojects.yona.queue.acceptance.JdbcQueueAcceptanceFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

class QueueControlAcceptanceTest {
    @Test
    fun manualRetriesPreserveAttemptsRecheckAuthorityAndDeduplicateAudits() {
        JdbcQueueAcceptanceFixture().use { fixture ->
            val definition = TaskDefinition("queue.acceptance.manual-recovery", 1, {}, handler = { context, _ ->
                when (context.attemptNo) {
                    1L -> throw PermanentTaskFailure("First generation failed")
                    2L -> throw RecoveryRequiredTaskFailure("Second generation requires inspection")
                    else -> context.checkpoint()
                }
            })
            val registry = TaskRegistry(listOf(definition))
            val manager = SharedEntityManagerCreator.createSharedEntityManager(
                (fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!,
            )
            val clock = fixture.contextQueueClock()
            val store = QueueWorkerStore(manager, fixture.transactionManager, clock, registry,
                fixture.dataSource, fixture.dataDirectory.toString())
            val actor = fixture.contextUserService().createUser(User(
                loginId = "queue-retry-${UUID.randomUUID()}", name = "Recovery admin",
                email = "${UUID.randomUUID()}@example.invalid", state = UserState.SITE_ADMIN,
            ))
            val actorId = actor.id!!
            val job = fixture.queue.enqueue(definition.type, 1, "{}".toByteArray(), Instant.EPOCH, null, "manual").jobId
            QueueWorkerRuntime(store, registry, clock, workers = 1, pollMillis = 20,
                shutdownGraceMillis = 1000, dataDirectory = fixture.dataDirectory.toString(),
                dbConnectionBudget = 4).use { runtime ->
                val control = QueueControl(manager, fixture.transactionManager, clock, registry, store, runtime)
                runtime.start()
                awaitStatus(fixture, job, "FAILED")
                val retryId = UUID.randomUUID().toString()
                control.retry(job, retryId, actorId)
                awaitStatus(fixture, job, "RECOVERY_REQUIRED")
                assertTrue(control.retry(job, retryId, actorId).deduplicated)
                assertEquals("COMMAND_ID_CONFLICT", assertThrows(QueueControlException::class.java) {
                    control.retry(job, retryId, actorId, reason = "Changed command payload")
                }.code)
                assertEquals(listOf(1L, 2L), fixture.attemptRows(job).map { it.attemptNo })
                assertEquals(listOf(1L, 2L), fixture.attemptRows(job).map { it.executionGeneration })
                assertEquals(listOf(1, 1), fixture.attemptRows(job).map { it.generationAttemptNo })
                assertEquals("RECOVERY_ACK_REQUIRED", assertThrows(QueueControlException::class.java) {
                    control.retry(job, UUID.randomUUID().toString(), actorId)
                }.code)
                assertEquals("RECOVERY_REASON_REQUIRED", assertThrows(QueueControlException::class.java) {
                    control.retry(job, UUID.randomUUID().toString(), actorId, recoveryAcknowledged = true)
                }.code)
                TransactionTemplate(fixture.transactionManager).executeWithoutResult {
                    manager.find(User::class.java, actorId).state = UserState.ACTIVE
                }
                assertEquals("FORBIDDEN", assertThrows(QueueControlException::class.java) {
                    control.retry(job, UUID.randomUUID().toString(), actorId, true, "Handler returned")
                }.code)
                TransactionTemplate(fixture.transactionManager).executeWithoutResult {
                    manager.find(User::class.java, actorId).state = UserState.SITE_ADMIN
                }
                control.retry(job, UUID.randomUUID().toString(), actorId, true, "Handler returned; effects inspected")
                awaitStatus(fixture, job, "SUCCEEDED")
                val attempts = fixture.attemptRows(job)
                assertEquals(listOf(1L, 2L, 3L), attempts.map { it.attemptNo })
                assertEquals(listOf(1L, 2L, 3L), attempts.map { it.executionGeneration })
                assertEquals(listOf("PERMANENT_FAILURE", "RECOVERY_REQUIRED", "SUCCEEDED"), attempts.map { it.status })
                assertEquals(3L, fixture.jobRow(job)?.attemptCount)
                val audits = JdbcTemplate(fixture.dataSource).queryForList(
                    "SELECT prior_status FROM queue_admin_audit WHERE job_id = ? ORDER BY created_at_epoch_ms",
                    String::class.java, job,
                )
                assertEquals(listOf("FAILED", "RECOVERY_REQUIRED"), audits.map { checkNotNull(it) }.sorted())

                val unsupported = fixture.queue.enqueue(definition.type, 2, "{}".toByteArray(), Instant.EPOCH, null, "manual").jobId
                awaitStatus(fixture, unsupported, "BLOCKED_UNSUPPORTED")
                assertEquals("UNSUPPORTED_HANDLER", assertThrows(QueueControlException::class.java) {
                    control.retry(unsupported, UUID.randomUUID().toString(), actorId)
                }.code)
                registry.register(TaskDefinition(definition.type, 2, {}, handler = { context, _ -> context.checkpoint() }))
                control.retry(unsupported, UUID.randomUUID().toString(), actorId)
                awaitStatus(fixture, unsupported, "SUCCEEDED")
                assertEquals(1L, fixture.attemptRows(unsupported).single().attemptNo)
                assertEquals(2L, fixture.attemptRows(unsupported).single().executionGeneration)
                assertEquals(2, fixture.jobRow(unsupported)?.payloadVersion)
            }
        }
    }

    private fun awaitStatus(fixture: JdbcQueueAcceptanceFixture, job: Long, status: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (fixture.jobRow(job)?.status != status && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals(status, fixture.jobRow(job)?.status)
    }
}
