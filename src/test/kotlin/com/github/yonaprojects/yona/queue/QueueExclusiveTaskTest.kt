package com.github.yonaprojects.yona.queue

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.SharedEntityManagerCreator
import java.time.Instant

class QueueExclusiveTaskTest {
    @Test
    fun exclusiveExecutionWaitsForHandlersAndBlocksClaimsUntilRecoveryIsAcknowledged() {
        withQueueReviewFixture { fixture ->
            val ordinary = TaskDefinition("queue.review.ordinary", 1, {}, handler = { _, _ -> })
            val exclusive = TaskDefinition("queue.review.exclusive", 1, {}, handler = { _, _ -> }, exclusive = true)
            fixture.registry.register(ordinary)
            fixture.registry.register(exclusive)
            val manager = SharedEntityManagerCreator.createSharedEntityManager(
                (fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!,
            )
            val store = QueueWorkerStore(manager, fixture.transactionManager, fixture.contextQueueClock(), fixture.registry,
                fixture.dataSource, fixture.dataDirectory.toString(), meterRegistry = SimpleMeterRegistry())
            fun enqueue(definition: TaskDefinition) = fixture.queue.enqueue(
                definition.type, 1, "{}".toByteArray(), Instant.EPOCH, null, "exclusive-test",
            ).jobId
            fun claim(id: Long, definition: TaskDefinition): QueueAttemptToken? {
                val candidate = checkNotNull(store.candidate(id))
                return store.claim(candidate, definition, fixture.registry.decodeAndValidate(definition, candidate.payload), "test-node")
            }
            val first = enqueue(ordinary)
            val restore = enqueue(exclusive)
            val later = enqueue(ordinary)
            val firstToken = checkNotNull(claim(first, ordinary))
            assertNull(claim(restore, exclusive))
            assertTrue(store.complete(firstToken, null, emptyList()))
            val restoreToken = checkNotNull(claim(restore, exclusive))
            assertNull(claim(later, ordinary))
            assertTrue(store.complete(restoreToken, RecoveryRequiredTaskFailure("Uncertain file replacement"), emptyList()))
            assertEquals("RECOVERY_REQUIRED", fixture.jobRow(restore)?.status)
            assertNull(claim(later, ordinary))
        }
    }
}
