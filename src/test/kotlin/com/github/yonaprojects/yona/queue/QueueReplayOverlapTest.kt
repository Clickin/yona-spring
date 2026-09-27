package com.github.yonaprojects.yona.queue

import com.github.yonaprojects.yona.queue.acceptance.JdbcQueueAcceptanceFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.SharedEntityManagerCreator
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class QueueReplayOverlapTest {
    @Test
    fun replaySafeNewAttemptUsesAnotherSlotWhileExpiredAttemptStillExists() {
        JdbcQueueAcceptanceFixture().use { fixture ->
            val firstEntered = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val secondEntered = CountDownLatch(1)
            val definition = TaskDefinition("queue.acceptance.overlapping-replay", 1, {},
                handler = { context, _ ->
                    if (context.attemptNo == 1L) {
                        firstEntered.countDown()
                        check(releaseFirst.await(15, TimeUnit.SECONDS))
                        context.checkpoint()
                    } else {
                        secondEntered.countDown()
                        context.checkpoint()
                    }
                }, replaySafe = true, laneLimit = 2)
            val registry = TaskRegistry(listOf(definition))
            val manager = SharedEntityManagerCreator.createSharedEntityManager(
                (fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!,
            )
            val clock = fixture.contextQueueClock()
            val store = QueueWorkerStore(manager, fixture.transactionManager, clock, registry,
                fixture.dataSource, fixture.dataDirectory.toString(), meterRegistry = io.micrometer.core.instrument.simple.SimpleMeterRegistry())
            val job = fixture.queue.enqueue(definition.type, 1, "{}".toByteArray(), Instant.EPOCH, null, "overlap").jobId
            QueueWorkerRuntime(store, registry, clock, io.micrometer.core.instrument.simple.SimpleMeterRegistry(), workers = 2, pollMillis = 20,
                shutdownGraceMillis = 1000, dataDirectory = fixture.dataDirectory.toString(),
                dbConnectionBudget = 4).use { runtime ->
                runtime.start()
                try {
                    assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
                    val jdbc = JdbcTemplate(fixture.dataSource)
                    jdbc.update("UPDATE queue_test_clock SET offset_ms = offset_ms + 120000 WHERE singleton_id = 1")
                    awaitStatus(fixture, job, "RETRY_WAIT")
                    jdbc.update("UPDATE queue_test_clock SET offset_ms = offset_ms + 60000 WHERE singleton_id = 1")
                    assertTrue(secondEntered.await(5, TimeUnit.SECONDS), "A durable replay claim was never dispatched")
                    assertEquals(1L, releaseFirst.count, "Expired handler was not still occupying its original slot")
                    awaitStatus(fixture, job, "SUCCEEDED")
                } finally {
                    releaseFirst.countDown()
                }
            }
            assertEquals("SUCCEEDED", fixture.jobRow(job)?.status)
            assertEquals(listOf("LEASE_LOST", "SUCCEEDED"), fixture.attemptRows(job).map { it.status })
            assertEquals(listOf(1L, 2L), fixture.attemptRows(job).map { it.attemptNo })
        }
    }

    private fun awaitStatus(fixture: JdbcQueueAcceptanceFixture, job: Long, status: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (fixture.jobRow(job)?.status != status && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals(status, fixture.jobRow(job)?.status)
    }
}
