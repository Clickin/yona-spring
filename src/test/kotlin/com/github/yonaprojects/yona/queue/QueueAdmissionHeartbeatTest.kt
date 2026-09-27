package com.github.yonaprojects.yona.queue

import com.github.yonaprojects.yona.queue.acceptance.JdbcQueueAcceptanceFixture
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class QueueAdmissionHeartbeatTest {
    @Test
    fun uncommittedAdmissionNeverLocksAnotherJobsHeartbeat() {
        JdbcQueueAcceptanceFixture().use { fixture ->
            val definition = TaskDefinition("queue.heartbeat.${UUID.randomUUID()}", 1, {}, handler = { _, _ -> })
            fixture.registry.register(definition)
            val manager = SharedEntityManagerCreator.createSharedEntityManager(
                (fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!,
            )
            val store = QueueWorkerStore(manager, fixture.transactionManager, fixture.contextQueueClock(), fixture.registry,
                fixture.dataSource, fixture.dataDirectory.toString(), meterRegistry = SimpleMeterRegistry())
            val receipt = fixture.queue.enqueue(definition.type, 1, "{}".toByteArray(), Instant.EPOCH, null, "heartbeat")
            val candidate = checkNotNull(store.candidate(receipt.jobId))
            val token = checkNotNull(store.claim(candidate, definition,
                fixture.registry.decodeAndValidate(definition, candidate.payload), "admission-heartbeat"))
            val admitted = CountDownLatch(1)
            val release = CountDownLatch(1)
            Executors.newFixedThreadPool(2).use { threads ->
                val producer = threads.submit {
                    TransactionTemplate(fixture.transactionManager).executeWithoutResult { status ->
                        fixture.queue.enqueue(definition.type, 1, "{}".toByteArray(), Instant.EPOCH, null, "heartbeat")
                        admitted.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        status.setRollbackOnly()
                    }
                }
                try {
                    assertTrue(admitted.await(5, TimeUnit.SECONDS))
                    assertTrue(threads.submit<Boolean> { store.heartbeat(token) }.get(1, TimeUnit.SECONDS),
                        "An admission count retained locks on an unrelated running job")
                } finally {
                    release.countDown()
                    producer.get(10, TimeUnit.SECONDS)
                }
            }
            assertTrue(store.complete(token, null, emptyList()))
            store.releaseAfterHandlerReturn(token)
        }
    }
}
