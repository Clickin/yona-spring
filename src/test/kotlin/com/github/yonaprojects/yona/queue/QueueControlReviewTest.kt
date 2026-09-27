package com.github.yonaprojects.yona.queue

import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserState
import com.github.yonaprojects.yona.queue.acceptance.JdbcQueueAcceptanceFixture
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

class QueueControlReviewTest {
    @Test
    fun abandoningEveryAttentionStateIsAuditedIdempotentAndResetsPriority() {
        Commands().use { test ->
            val failed = test.failed(PermanentTaskFailure("failed"))
            val recovery = test.failed(RecoveryRequiredTaskFailure("uncertain"))
            val unsupported = test.fixture.queue.enqueue("queue.review.missing", 1, "{}".toByteArray(), Instant.EPOCH, null, "review").jobId
            assertTrue(test.store.markUnsupported(checkNotNull(test.store.candidate(unsupported))))
            assertEquals("BLOCKED_UNSUPPORTED", test.fixture.jobRow(unsupported)?.status)
            val failedBefore = test.fixture.jdbc.queryForObject("SELECT counter_value FROM queue_meta WHERE counter_name = 'failed-jobs'", Long::class.java)!!
            assertEquals("RECOVERY_ACK_REQUIRED", assertThrows(QueueControlException::class.java) {
                test.control.abandon(recovery, UUID.randomUUID().toString(), test.actor, false, "Inspected effects")
            }.code)
            assertEquals("INVALID_REASON", assertThrows(QueueControlException::class.java) {
                test.control.abandon(failed, UUID.randomUUID().toString(), test.actor, false, " ")
            }.code)
            for (job in listOf(failed, recovery, unsupported)) {
                val command = UUID.randomUUID().toString()
                assertEquals(QueueStatus.CANCELLED, test.control.abandon(job, command, test.actor, true, "Inspected effects").status)
                assertTrue(test.control.abandon(job, command, test.actor, true, "Inspected effects").deduplicated)
                assertEquals(0, test.fixture.jdbc.queryForObject("SELECT priority FROM queue_job WHERE id = ?", Int::class.java, job))
                assertEquals(1, test.fixture.jdbc.queryForObject("SELECT COUNT(*) FROM queue_admin_audit WHERE command_id = ?", Int::class.java, command))
            }
            assertEquals(failedBefore - 1, test.fixture.jdbc.queryForObject("SELECT counter_value FROM queue_meta WHERE counter_name = 'failed-jobs'", Long::class.java))
            assertEquals("INVALID_TRANSITION", assertThrows(QueueControlException::class.java) {
                test.control.abandon(test.enqueue(), UUID.randomUUID().toString(), test.actor, false, "Not terminal")
            }.code)
        }
    }

    @Test
    fun priorityOrdersDueJobsAndManualRetryClearsIt() {
        Commands().use { test ->
            val first = test.enqueue()
            val second = test.enqueue()
            val third = test.enqueue()
            val command = UUID.randomUUID().toString()
            assertTrue(test.control.prioritize(third, command, test.actor, true).changed)
            assertTrue(test.control.prioritize(third, command, test.actor, true).deduplicated)
            assertEquals(listOf(third, first, second), test.store.dueCandidateIds(null, 64).map { it.id })
            val page = test.store.dueCandidateIds(null, 1).single()
            assertEquals(listOf(first, second), test.store.dueCandidateIds(page, 64).map { it.id })
            assertTrue(test.store.dueCandidateIds(null, 64, setOf(test.definition.type)).isEmpty())
            test.control.prioritize(third, UUID.randomUUID().toString(), test.actor, false)
            assertEquals(listOf(first, second, third), test.store.dueCandidateIds(null, 64).map { it.id })
            val failed = test.failed(PermanentTaskFailure("failed"))
            test.control.retry(failed, UUID.randomUUID().toString(), test.actor)
            assertEquals(0, test.fixture.jdbc.queryForObject("SELECT priority FROM queue_job WHERE id = ?", Int::class.java, failed))
            val denied = test.failed(PermanentTaskFailure("failed"))
            assertEquals("INVALID_TRANSITION", assertThrows(QueueControlException::class.java) {
                test.control.prioritize(denied, UUID.randomUUID().toString(), test.actor, true)
            }.code)
            TransactionTemplate(test.fixture.transactionManager).executeWithoutResult {
                test.manager.find(User::class.java, test.actor).state = UserState.ACTIVE
            }
            assertEquals("FORBIDDEN", assertThrows(QueueControlException::class.java) {
                test.control.abandon(denied, UUID.randomUUID().toString(), test.actor, false, "Inspected")
            }.code)
        }
    }

    @Test
    fun repeatedCancelAuditsBothNoOpStatesWithoutInvalidatingTheProjection() {
        Commands().use { test ->
            for (running in listOf(false, true)) {
                val job = test.enqueue()
                val token = if (running) test.claim(job) else null
                test.control.cancel(job, UUID.randomUUID().toString(), test.actor)
                val generation = test.fixture.jdbc.queryForObject("SELECT counter_value FROM queue_meta WHERE counter_name = 'change-generation'", Long::class.java)
                val command = UUID.randomUUID().toString()
                assertFalse(test.control.cancel(job, command, test.actor).changed)
                assertEquals(generation, test.fixture.jdbc.queryForObject("SELECT counter_value FROM queue_meta WHERE counter_name = 'change-generation'", Long::class.java))
                assertEquals(1, test.fixture.jdbc.queryForObject("SELECT COUNT(*) FROM queue_admin_audit WHERE command_id = ?", Int::class.java, command))
                token?.let { test.store.complete(it, CooperativeTaskCancellation(), emptyList()) }
            }
        }
    }

    private class Commands : AutoCloseable {
        val fixture = JdbcQueueAcceptanceFixture()
        val definition = TaskDefinition("queue.review.commands", 1, {}, handler = { _, _ -> })
        val manager = SharedEntityManagerCreator.createSharedEntityManager((fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!)
        val meters = SimpleMeterRegistry()
        val store = QueueWorkerStore(manager, fixture.transactionManager, fixture.contextQueueClock(), fixture.registry,
            fixture.dataSource, fixture.dataDirectory.toString(), meterRegistry = meters)
        val runtime = QueueWorkerRuntime(store, fixture.registry, fixture.contextQueueClock(), meters,
            workers = 1, dataDirectory = fixture.dataDirectory.toString(), dbConnectionBudget = 4)
        val actor = fixture.contextUserService().createUser(User(
            loginId = "queue-command-${UUID.randomUUID()}", name = "Queue admin",
            email = "${UUID.randomUUID()}@example.invalid", state = UserState.SITE_ADMIN,
        )).id!!
        val control = QueueControl(manager, fixture.transactionManager, fixture.contextQueueClock(), fixture.registry, store, runtime, fixture.queue)

        init { fixture.registry.register(definition) }

        fun enqueue() = fixture.queue.enqueue(definition.type, 1, "{}".toByteArray(), Instant.EPOCH, null, "review").jobId
        fun claim(job: Long): QueueAttemptToken {
            val candidate = checkNotNull(store.candidate(job))
            return checkNotNull(store.claim(candidate, definition, fixture.registry.decodeAndValidate(definition, candidate.payload), "command-test"))
        }
        fun failed(failure: Throwable): Long = enqueue().also { job ->
            control.prioritize(job, UUID.randomUUID().toString(), actor, true)
            val token = claim(job)
            check(store.complete(token, failure, emptyList()))
            store.releaseAfterHandlerReturn(token)
        }
        override fun close() {
            runtime.close()
            try { removeQueueReviewJobs(fixture) } finally {
                fixture.close()
                meters.close()
            }
        }
    }
}
