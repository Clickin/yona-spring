package com.github.yonaprojects.yona.queue

import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserState
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.SharedEntityManagerCreator
import java.time.Instant
import java.util.UUID

class QueueCancelLeaseExpiryTest {
    /** An administrator already asked for cancellation; losing the lease must not demand a second decision. */
    @Test
    fun leaseExpiryAfterCancelRequestEndsCancelled() {
        withQueueReviewFixture { fixture ->
            val definition = TaskDefinition("queue.review.cancel-lease", 1, {}, handler = { _, _ -> })
            fixture.registry.register(definition)
            val manager = SharedEntityManagerCreator.createSharedEntityManager(
                (fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!,
            )
            val clock = fixture.contextQueueClock()
            val meters = SimpleMeterRegistry()
            val store = QueueWorkerStore(manager, fixture.transactionManager, clock, fixture.registry,
                fixture.dataSource, fixture.dataDirectory.toString(), leaseMillis = 300, meterRegistry = meters)
            val actor = fixture.contextUserService().createUser(User(
                loginId = "queue-review-${UUID.randomUUID()}", name = "Queue admin",
                email = "${UUID.randomUUID()}@example.invalid", state = UserState.SITE_ADMIN,
            ))
            val job = fixture.queue.enqueue(definition.type, 1, "{}".toByteArray(), Instant.EPOCH, null, "review").jobId
            val candidate = checkNotNull(store.candidate(job))
            checkNotNull(store.claim(candidate, definition, fixture.registry.decodeAndValidate(definition, candidate.payload), "lost-node"))
            QueueWorkerRuntime(store, fixture.registry, clock, meters, workers = 1, pollMillis = 20, leaseMillis = 300,
                heartbeatMillis = 100, dataDirectory = fixture.dataDirectory.toString(), dbConnectionBudget = 4).use { runtime ->
                val control = QueueControl(manager, fixture.transactionManager, clock, fixture.registry, store, runtime, fixture.queue)
                control.cancel(job, UUID.randomUUID().toString(), actor.id!!)
            }
            assertEquals("CANCEL_REQUESTED", fixture.jobRow(job)?.status)
            Thread.sleep(600)
            assertTrue(store.recoverExpired(job))
            assertEquals("CANCELLED", fixture.jobRow(job)?.status)
            assertEquals(listOf("LEASE_LOST"), fixture.attemptRows(job).map { it.status })
            assertEquals("LEASE_LOST", fixture.jdbc.queryForObject("SELECT error_code FROM queue_job WHERE id = ?", String::class.java, job))
            assertNotNull(fixture.jdbc.queryForObject("SELECT finished_at_epoch_ms FROM queue_job WHERE id = ?", Long::class.javaObjectType, job))
        }
    }
}
