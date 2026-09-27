package com.github.yonaprojects.yona.queue

import com.github.yonaprojects.yona.queue.acceptance.JdbcQueueAcceptanceFixture
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class QueueAdmissionCapacityTest {
    @Test
    fun capacityIncludesUncommittedAdmissionsButNotIdempotentReplaysAndRollbackClearsIt() {
        JdbcQueueAcceptanceFixture().use { fixture ->
            val pending = pending(fixture)
            val queue = queue(fixture, pending + 2)
            val transaction = TransactionTemplate(fixture.transactionManager)
            val failure = assertThrows(QueueAdmissionException::class.java) {
                transaction.executeWithoutResult {
                    val key = UUID.randomUUID().toString()
                    val first = enqueue(queue, key)
                    assertEquals(first.jobId, enqueue(queue, key).jobId)
                    enqueue(queue)
                    enqueue(queue)
                }
            }
            assertEquals("QUEUE_FULL", failure.code)
            assertEquals(pending, pending(fixture))
            transaction.executeWithoutResult {
                enqueue(queue)
                enqueue(queue)
            }
            assertEquals(pending + 2, pending(fixture))
            assertEquals("QUEUE_FULL", assertThrows(QueueAdmissionException::class.java) {
                transaction.executeWithoutResult { enqueue(queue) }
            }.code)
        }
    }

    @Test
    fun enqueueWakesOnlyAfterOuterCommitAndNeverOnRollbackOrIdempotentReplay() {
        JdbcQueueAcceptanceFixture().use { fixture ->
            val wakes = AtomicInteger()
            val queue = queue(fixture, pending(fixture) + 10, ApplicationEventPublisher {
                if (it is QueueWorkAvailable) wakes.incrementAndGet()
            })
            val transaction = TransactionTemplate(fixture.transactionManager)
            transaction.executeWithoutResult { status ->
                enqueue(queue)
                assertEquals(0, wakes.get())
                status.setRollbackOnly()
            }
            assertEquals(0, wakes.get())
            val key = UUID.randomUUID().toString()
            transaction.executeWithoutResult {
                enqueue(queue, key)
                assertEquals(0, wakes.get())
            }
            assertEquals(1, wakes.get())
            transaction.executeWithoutResult { enqueue(queue, key) }
            assertEquals(1, wakes.get())
        }
    }

    @Test
    fun exhaustedPoolRejectsWithinTwoSecondsAndNeverLeaksALateAcquisition() {
        JdbcQueueAcceptanceFixture().use { fixture ->
            val source = fixture.dataSource.unwrap(HikariDataSource::class.java)
            val queue = queue(fixture, pending(fixture) + 10)
            fixture.contextQueueClock().stop()
            fixture.contextQueueClock().verifySynchronization()
            val held = (1 until source.maximumPoolSize).map { source.connection }
            try {
                val started = System.nanoTime()
                assertEquals("QUEUE_UNAVAILABLE", assertThrows(QueueAdmissionException::class.java) {
                    TransactionTemplate(fixture.transactionManager).executeWithoutResult { enqueue(queue) }
                }.code)
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                assertTrue(elapsed in 1800..2500, "Admission waited ${elapsed}ms instead of its 2s acquisition bound")
            } finally {
                held.forEach { it.close() }
            }
            // Every permit is usable immediately; no timed-out background borrower can steal one later.
            val reusable = (1..source.maximumPoolSize).map { source.connection }
            reusable.forEach { it.close() }
            assertEquals(0, source.hikariPoolMXBean.threadsAwaitingConnection)
        }
    }

    private fun pending(fixture: JdbcQueueAcceptanceFixture): Long = JdbcTemplate(fixture.dataSource).queryForObject(
        "SELECT COUNT(*) FROM queue_job WHERE status IN ('QUEUED','RUNNING','RETRY_WAIT','CANCEL_REQUESTED')",
        Long::class.java,
    )!!

    private fun queue(fixture: JdbcQueueAcceptanceFixture, maximum: Long, events: ApplicationEventPublisher? = null): Queue {
        val manager = SharedEntityManagerCreator.createSharedEntityManager(
            (fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!,
        )
        return Queue(manager, fixture.transactionManager, fixture.contextQueueClock(), fixture.registry,
            fixture.dataSource, maxPending = maximum, events = events)
    }

    private fun enqueue(queue: Queue, key: String? = null) = queue.enqueue(
        "queue.admission.capacity", 1, "{}".toByteArray(), Instant.parse("2100-01-01T00:00:00Z"), key, "capacity",
    )
}
