package com.github.yonaprojects.yona.queue

import com.github.yonaprojects.yona.queue.acceptance.JdbcQueueAcceptanceFixture
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.SharedEntityManagerCreator
import java.time.Instant
import java.util.concurrent.TimeUnit

class QueueMetricsAcceptanceTest {
    @Test
    fun lifecycleSamplerPublishesEnqueuedJobsWithoutSpringScheduledInvocation() {
        JdbcQueueAcceptanceFixture().use { fixture ->
            val factory = (fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!
            val registry = SimpleMeterRegistry()
            try {
                val metrics = QueueMetrics(SharedEntityManagerCreator.createSharedEntityManager(factory),
                    fixture.transactionManager, fixture.contextQueueClock(), registry, refreshMillis = 20)
                val baseline = fixture.jdbc.queryForObject("SELECT COUNT(*) FROM queue_job WHERE status = 'QUEUED'", Long::class.java)!!
                val job = fixture.queue.enqueue("queue.metrics.scheduled", 1, "{}".toByteArray(), Instant.now().plusSeconds(3600), null, "metrics").jobId
                val expected = (baseline + 1).toDouble()
                val queued = registry.get("yona.queue.jobs").tag("status", "QUEUED").gauge()
                try {
                    metrics.start()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (queued.value() != expected && System.nanoTime() < deadline) Thread.sleep(20)
                    assertEquals(expected, queued.value())
                } finally {
                    metrics.stop()
                    fixture.jdbc.update("DELETE FROM queue_job WHERE id = ?", job)
                }
                assertFalse(metrics.isRunning())
            } finally {
                registry.close()
            }
        }
    }
}
