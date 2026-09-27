package com.github.yonaprojects.yona.queue

import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserState
import com.github.yonaprojects.yona.queue.acceptance.JdbcQueueAcceptanceFixture
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class QueueMetricsAcceptanceTest {
    @Test
    fun committedCountsSurviveRollbackRetryCapacityAndConcurrentColdInitialization() {
        val meters = SimpleMeterRegistry()
        try {
            JdbcQueueAcceptanceFixture().use { fixture ->
                val transactions = fixture.transactionManager
                val manager = SharedEntityManagerCreator.createSharedEntityManager((transactions as JpaTransactionManager).entityManagerFactory!!)
                val clock = fixture.contextQueueClock()
                val registry = fixture.registry
                val definition = TaskDefinition("queue.metrics.acceptance", 1, {}, handler = { _, _ -> })
                registry.register(definition)
                val queue = Queue(manager, transactions, clock, registry, fixture.dataSource, maxPending = 1)
                val store = QueueWorkerStore(manager, transactions, clock, registry, fixture.dataSource,
                    fixture.dataDirectory.toString(), meterRegistry = meters)
                val metrics = QueueMetrics(manager, transactions, clock, meters)
                val jdbc = JdbcTemplate(fixture.dataSource)
                val actor = fixture.contextUserService().createUser(User(
                    loginId = "metrics-${UUID.randomUUID()}", name = "Metrics admin",
                    email = "${UUID.randomUUID()}@example.invalid", state = UserState.SITE_ADMIN,
                )).id!!
                fun admit(due: Instant = Instant.EPOCH): Long = TransactionTemplate(transactions).execute {
                    queue.enqueue(definition.type, 1, "{}".toByteArray(), due, null, "metrics").jobId
                }!!
                fun claim(id: Long): QueueAttemptToken {
                    val candidate = checkNotNull(store.candidate(id))
                    return checkNotNull(store.claim(candidate, definition, registry.decodeAndValidate(definition, candidate.payload), "metrics"))
                }
                fun failedGauge(): Double {
                    metrics.refresh()
                    return meters.get("yona.queue.jobs").tag("status", "FAILED").gauge().value()
                }
                fun failures() = meters.get("yona.queue.attempts").tag("outcome", "PERMANENT_FAILURE").counter().count()

                QueueWorkerRuntime(store, registry, clock, meters, workers = 1, dataDirectory = fixture.dataDirectory.toString()).use { runtime ->
                    val control = QueueControl(manager, transactions, clock, registry, store, runtime, queue)
                    val id = admit()
                    val token = claim(id)
                    TransactionTemplate(transactions).executeWithoutResult { transaction ->
                        assertTrue(store.complete(token, PermanentTaskFailure("permanent"), emptyList()))
                        assertEquals(0.0, failures(), "Uncommitted completion must not publish a metric")
                        transaction.setRollbackOnly()
                    }
                    assertEquals(QueueStatus.RUNNING, fixture.queue.find(id)?.status)
                    assertEquals(0.0, failures())
                    assertEquals(0.0, failedGauge())
                    assertTrue(store.complete(token, PermanentTaskFailure("permanent"), emptyList()))
                    store.releaseAfterHandlerReturn(token)
                    assertEquals(1.0, failures())
                    assertEquals(1.0, failedGauge())

                    TransactionTemplate(transactions).executeWithoutResult { transaction ->
                        control.retry(id, UUID.randomUUID().toString(), actor)
                        transaction.setRollbackOnly()
                    }
                    assertEquals(QueueStatus.FAILED, fixture.queue.find(id)?.status)
                    assertEquals(1.0, failedGauge(), "Rolled-back retry must not decrement FAILED")
                    val pending = admit(Instant.parse("2100-01-01T00:00:00Z"))
                    val rejectedCommand = UUID.randomUUID().toString()
                    assertEquals("QUEUE_FULL", assertThrows(QueueControlException::class.java) {
                        control.retry(id, rejectedCommand, actor)
                    }.code)
                    assertEquals(QueueStatus.FAILED, fixture.queue.find(id)?.status)
                    assertEquals(1.0, failedGauge())
                    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM queue_admin_audit WHERE command_id = ?", Int::class.java, rejectedCommand))
                    control.cancel(pending, UUID.randomUUID().toString(), actor)
                    control.retry(id, UUID.randomUUID().toString(), actor)
                    assertEquals(0.0, failedGauge())
                    val retried = claim(id)
                    assertEquals(1.0, meters.get("yona.queue.retries").counter().count())
                    assertTrue(store.complete(retried, null, emptyList()))
                    store.releaseAfterHandlerReturn(retried)
                    assertEquals(1.0, meters.get("yona.queue.attempts").tag("outcome", "SUCCEEDED").counter().count())

                    val retained = claim(admit())
                    assertTrue(store.complete(retained, PermanentTaskFailure("retained failure"), emptyList()))
                    store.releaseAfterHandlerReturn(retained)
                    assertEquals(1.0, failedGauge())
                }
                // Simulate the previous queue schema: jobs exist, derived metric row does not.
                assertEquals(1, jdbc.update("DELETE FROM queue_meta WHERE counter_name = ?", QueueMetrics.FAILED_COUNTER))
                val initializers = Executors.newFixedThreadPool(2)
                try {
                    val starts = (1..2).map { initializers.submit { queue.afterPropertiesSet() } }
                    starts.forEach { it.get(15, TimeUnit.SECONDS) }
                } finally {
                    initializers.shutdownNow()
                }
                assertEquals(1.0, failedGauge(), "Concurrent cold initialization must preserve the existing failed job")
                assertEquals(2.0, failures(), "Backfill must not replay process-local completion counters")
            }
        } finally {
            meters.close()
        }
    }
}
