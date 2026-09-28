package com.github.yonaprojects.yona.queue.acceptance

import com.github.yonaprojects.yona.queue.QueueWorkerRuntime
import com.github.yonaprojects.yona.queue.QueueWorkerStore
import com.github.yonaprojects.yona.queue.StaleAttempt
import com.github.yonaprojects.yona.queue.TaskDefinition
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class QueueProgressCoalescingAcceptanceTest {
    @Test
    fun rolledBackProgressDoesNotSuppressTheNextCommittedStage() {
        JdbcQueueAcceptanceFixture().use { fixture ->
            val jdbc = JdbcTemplate(fixture.dataSource)
            val ready = CountDownLatch(1)
            val release = CountDownLatch(1)
            val type = "queue.acceptance.progress-rollback-${UUID.randomUUID()}"
            fixture.registry.register(TaskDefinition(type, 1, {}, handler = { context, _ ->
                context.progress("scan", mapOf("items" to 1L))
                try {
                    context.fencedDb {
                        context.progress("upload", mapOf("items" to 2L))
                        throw IllegalArgumentException("Intentional rollback")
                    }
                } catch (_: IllegalArgumentException) {
                    // The next upload is a real stage transition, not a coalesced duplicate.
                }
                context.progress("upload", mapOf("items" to 3L))
                ready.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }))
            runtime(fixture).use { worker ->
                worker.start()
                val id = TransactionTemplate(fixture.transactionManager).execute {
                    fixture.queue.enqueue(type, 1, "{}".toByteArray(), Instant.EPOCH, null, "rollback-test").jobId
                }!!
                try {
                    assertTrue(ready.await(10, TimeUnit.SECONDS))
                    assertEquals("upload", jdbc.queryForObject(
                        "SELECT progress_stage FROM queue_job WHERE id = ?", String::class.java, id,
                    ))
                    assertEquals("3", progressCounter(jdbc, "queue_job", id))
                    assertEquals("3", progressCounter(jdbc, "queue_attempt", id))
                } finally {
                    release.countDown()
                }
                awaitStatus(fixture, id, "SUCCEEDED")
            }
        }
    }

    @Test
    fun coalescesValidatedSnapshotsAndMergesTheLatestValueAtFencedCompletion() {
        JdbcQueueAcceptanceFixture().use { fixture ->
            val jdbc = JdbcTemplate(fixture.dataSource)
            val type = "queue.acceptance.progress-coalesce-${UUID.randomUUID()}"
            val burstReady = CountDownLatch(1)
            val allowStageChange = CountDownLatch(1)
            val stageChangeCommitted = CountDownLatch(1)
            val allowReturn = CountDownLatch(1)
            val concurrentProgressStarted = CountDownLatch(1)
            val burstWrites = AtomicLong(-1)
            val progressWindowStartedAt = AtomicLong(0)
            val invalidRejected = AtomicReference(false)
            val concurrentResult = AtomicReference<String?>(null)
            val concurrentThread = AtomicReference<Thread?>(null)

            fun generation(): Long = jdbc.queryForObject(
                "SELECT counter_value FROM queue_meta WHERE counter_name = 'change-generation'",
                Long::class.javaObjectType,
            )!!

            fixture.registry.register(TaskDefinition(
                type, 1,
                validate = { require(it.isObject) },
                handler = { context, _ ->
                    val before = generation()
                    val started = System.nanoTime()
                    progressWindowStartedAt.set(started)
                    repeat(100) { index -> context.progress("scan", mapOf("items" to (index + 1L))) }
                    val mutableCounters = mutableMapOf("items" to 100L)
                    context.progress("scan", mutableCounters)
                    mutableCounters["items"] = 999L
                    try {
                        context.progress("scan", mapOf("items" to -1L))
                    } catch (_: IllegalArgumentException) {
                        invalidRejected.set(true)
                    }
                    burstWrites.set(generation() - before)
                    check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5_000)
                    burstReady.countDown()
                    check(allowStageChange.await(8, TimeUnit.SECONDS))

                    // A stage transition bypasses the same-stage five-second coalescing window.
                    context.progress("upload", mapOf("items" to 101L))
                    stageChangeCommitted.countDown()
                    check(allowReturn.await(10, TimeUnit.SECONDS))
                    context.progress("upload", mapOf("items" to 102L))

                    // Race one legitimate latest snapshot with closeForHandlerReturn. Either it
                    // wins before close and must be persisted, or it is rejected as stale.
                    val updater = Thread {
                        concurrentProgressStarted.countDown()
                        try {
                            context.progress("upload", mapOf("items" to 999L))
                            concurrentResult.set("accepted")
                        } catch (_: StaleAttempt) {
                            concurrentResult.set("rejected")
                        } catch (failure: Throwable) {
                            concurrentResult.set("error:${failure.javaClass.name}")
                        }
                    }
                    concurrentThread.set(updater)
                    updater.start()
                    check(concurrentProgressStarted.await(2, TimeUnit.SECONDS))
                },
            ))

            val runtime = runtime(fixture)
            try {
                runtime.start()
                val jobId = TransactionTemplate(fixture.transactionManager).execute {
                    fixture.queue.enqueue(type, 1, "{}".toByteArray(), Instant.EPOCH, null, "sse-progress-test").jobId
                }!!
                try {
                    assertTrue(burstReady.await(10, TimeUnit.SECONDS), "handler did not reach the burst gate")
                    assertTrue(burstWrites.get() in 0L..1L,
                        "100 same-stage updates inside five seconds must cause at most one projection write")
                    assertTrue(invalidRejected.get(), "negative counters remain invalid inside the coalescing window")
                    val flushDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7)
                    var periodicValue: String? = null
                    while (System.nanoTime() < flushDeadline) {
                        periodicValue = progressCounterOrNull(jdbc, "queue_job", jobId)
                        assertTrue(periodicValue != "999", "progress must snapshot a mutable caller map")
                        if (periodicValue == "100") break
                        Thread.sleep(50)
                    }
                    assertEquals("100", periodicValue,
                        "latest pending progress must flush without another producer call while handler is running")
                    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - progressWindowStartedAt.get()) >= 5_000,
                        "same-stage periodic flush must not occur before five seconds")
                    assertEquals("RUNNING", fixture.jobRow(jobId)?.status,
                        "periodic progress flush must not wait for handler completion")

                    allowStageChange.countDown()
                    assertTrue(stageChangeCommitted.await(10, TimeUnit.SECONDS), "stage transition did not commit")
                    assertEquals("upload", jdbc.queryForObject(
                        "SELECT progress_stage FROM queue_job WHERE id = ?", String::class.java, jobId,
                    ))
                    assertEquals("101", progressCounter(jdbc, "queue_job", jobId))
                    allowReturn.countDown()
                    awaitStatus(fixture, jobId, "SUCCEEDED")
                    concurrentThread.get()?.join(5_000)
                    assertTrue(!concurrentThread.get()!!.isAlive, "concurrent progress call did not finish")
                    val finalValue = when (concurrentResult.get()) {
                        "accepted" -> "999"
                        "rejected" -> "102"
                        else -> throw AssertionError("unexpected concurrent progress outcome: ${concurrentResult.get()}")
                    }
                    assertEquals(finalValue, progressCounter(jdbc, "queue_job", jobId))
                    assertEquals(finalValue, progressCounter(jdbc, "queue_attempt", jobId))
                    assertEquals("upload", jdbc.queryForObject(
                        "SELECT progress_stage FROM queue_attempt WHERE job_id = ? AND attempt_no = 1",
                        String::class.java, jobId,
                    ))
                } finally {
                    allowStageChange.countDown()
                    allowReturn.countDown()
                }
            } finally {
                runtime.close()
            }
        }
    }

    private fun progressCounter(jdbc: JdbcTemplate, table: String, jobId: Long): String =
        checkNotNull(progressCounterOrNull(jdbc, table, jobId))

    private fun progressCounterOrNull(jdbc: JdbcTemplate, table: String, jobId: Long): String? {
        val sql = if (table == "queue_job") {
            "SELECT progress_json FROM queue_job WHERE id = ?"
        } else {
            "SELECT progress_json FROM queue_attempt WHERE job_id = ? AND attempt_no = 1"
        }
        val encoded = jdbc.queryForObject(sql, String::class.java, jobId) ?: return null
        return checkNotNull(JsonMapper.builder().build().readTree(encoded)["items"]).asText()
    }

    private fun runtime(fixture: JdbcQueueAcceptanceFixture): QueueWorkerRuntime {
        val entityManager = SharedEntityManagerCreator.createSharedEntityManager(
            (fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!,
        ) as EntityManager
        val store = QueueWorkerStore(
            entityManager, fixture.transactionManager, fixture.contextQueueClock(), fixture.registry,
            fixture.dataSource, fixture.dataDirectory.toString(),
            meterRegistry = io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
        )
        return QueueWorkerRuntime(
            store, fixture.registry, fixture.contextQueueClock(), io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            workers = 1, pollMillis = 20, shutdownGraceMillis = 1_000,
            dataDirectory = fixture.dataDirectory.toString(), dbConnectionBudget = 4,
        )
    }

    private fun awaitStatus(fixture: JdbcQueueAcceptanceFixture, jobId: Long, expected: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (fixture.jobRow(jobId)?.status != expected && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals(expected, fixture.jobRow(jobId)?.status)
    }
}
