package com.github.yonaprojects.yona.queue.acceptance

import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserState
import com.github.yonaprojects.yona.queue.PermanentTaskFailure
import com.github.yonaprojects.yona.queue.QueueAdmissionException
import com.github.yonaprojects.yona.queue.QueueControl
import com.github.yonaprojects.yona.queue.QueueControlException
import com.github.yonaprojects.yona.queue.QueueWorkerRuntime
import com.github.yonaprojects.yona.queue.QueueWorkerStore
import com.github.yonaprojects.yona.queue.RetryableTaskFailure
import com.github.yonaprojects.yona.queue.TaskDefinition
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class QueueProducerBoundsAcceptanceTest {
    @Test
    fun resourcesRejectMoreThanSixteenDistinctKeysAndDeduplicateBeforeTheLimit() {
        JdbcQueueAcceptanceFixture().use { fixture ->
            val jdbc = JdbcTemplate(fixture.dataSource)
            val tooManyType = taskType("resources-over-limit")
            fixture.registry.register(TaskDefinition(
                tooManyType, 1, validate = { require(it.isObject) },
                resourceKeys = { (1..17).map { "repo:$it" } },
            ))
            val jobCountBefore = jdbc.queryForObject("SELECT COUNT(*) FROM queue_job", Long::class.java)!!
            val failure = assertThrows(QueueAdmissionException::class.java) {
                admit(fixture, tooManyType)
            }
            assertEquals("INVALID_PAYLOAD", failure.code)
            assertEquals(jobCountBefore, jdbc.queryForObject("SELECT COUNT(*) FROM queue_job", Long::class.java))
            assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM queue_job WHERE task_type = ?", Int::class.java, tooManyType,
            ))

            val repeatedKeys = taskType("resources-deduplicated")
            val distinctKeys = (1..16).map { "repo:$it" }
            fixture.registry.register(TaskDefinition(
                repeatedKeys, 1, validate = { require(it.isObject) },
                resourceKeys = { distinctKeys + listOf("repo:2", "repo:1") },
            ))
            val jobId = admit(fixture, repeatedKeys)
            val persisted = jdbc.queryForList(
                "SELECT resource_key FROM queue_job_resource WHERE job_id = ? ORDER BY resource_key",
                String::class.java, jobId,
            )
            assertEquals(distinctKeys.sorted(), persisted)
        }
    }

    @Test
    fun progressAndFailureProjectionsUseUnicodeCodepointsAndRejectInvalidUpdatesAtomically() {
        JdbcQueueAcceptanceFixture().use { fixture ->
            val jdbc = JdbcTemplate(fixture.dataSource)
            val stage160 = MUSIC_SYMBOL.repeat(160)
            val stage161 = MUSIC_SYMBOL.repeat(161)
            val counters = ('a'..'p').associate { "count-$it" to Long.MAX_VALUE }
            val progressType = taskType("progress-bounds")
            fixture.registry.register(TaskDefinition(
                progressType, 1, validate = { require(it.isObject) },
                handler = { context, _ ->
                    context.progress(stage160, counters)
                    assertThrows(IllegalArgumentException::class.java) { context.progress(stage161, counters) }
                    assertThrows(IllegalArgumentException::class.java) {
                        context.progress(stage160, counters + ("count-q" to Long.MAX_VALUE))
                    }
                    assertThrows(IllegalArgumentException::class.java) {
                        context.progress(stage160, counters + ("count-a" to -1L))
                    }
                    assertThrows(IllegalArgumentException::class.java) {
                        context.progress("unsafe\u0001stage", counters)
                    }
                    assertThrows(IllegalArgumentException::class.java) {
                        context.progress("\uD834", counters)
                    }
                    assertThrows(IllegalArgumentException::class.java) {
                        context.progress(stage160, counters + ("bad\u0001key" to 1L))
                    }
                    context.checkpoint()
                },
            ))

            val boundarySummary = "x".repeat(2047) + MUSIC_SYMBOL
            val errorType = taskType("error-summary-bounds")
            fixture.registry.register(TaskDefinition(
                errorType, 1, validate = { require(it.isObject) },
                handler = { _, _ -> throw PermanentTaskFailure("$boundarySummary-tail") },
            ))

            runtime(fixture).use { worker ->
                worker.start()
                val progressJob = admit(fixture, progressType)
                val failedJob = admit(fixture, errorType)
                awaitStatus(fixture, progressJob, "SUCCEEDED")
                awaitStatus(fixture, failedJob, "FAILED")

                val persistedStage = jdbc.queryForObject(
                    "SELECT progress_stage FROM queue_job WHERE id = ?", String::class.java, progressJob,
                )!!
                assertEquals(stage160, persistedStage)
                assertEquals(160, persistedStage.codePointCount(0, persistedStage.length))
                for (table in listOf("queue_job", "queue_attempt")) {
                    val json = if (table == "queue_job") {
                        jdbc.queryForObject("SELECT progress_json FROM queue_job WHERE id = ?", String::class.java, progressJob)
                    } else {
                        jdbc.queryForObject(
                            "SELECT progress_json FROM queue_attempt WHERE job_id = ? AND attempt_no = 1",
                            String::class.java, progressJob,
                        )
                    }
                    val parsed = JsonMapper.builder().build().readTree(checkNotNull(json))
                    assertEquals(16, parsed.size())
                    counters.keys.forEach { key ->
                        assertEquals(Long.MAX_VALUE.toString(), checkNotNull(parsed[key]).asText())
                    }
                }

                val jobSummary = jdbc.queryForObject(
                    "SELECT error_summary FROM queue_job WHERE id = ?", String::class.java, failedJob,
                )!!
                val attemptSummary = jdbc.queryForObject(
                    "SELECT error_summary FROM queue_attempt WHERE job_id = ? AND attempt_no = 1",
                    String::class.java, failedJob,
                )!!
                assertEquals(boundarySummary, jobSummary)
                assertEquals(boundarySummary, attemptSummary)
                for (summary in listOf(jobSummary, attemptSummary)) {
                    assertTrue(summary.codePointCount(0, summary.length) <= 2048)
                    assertWellFormedUtf16(summary)
                    assertFalse(summary.any { it.code < 0x20 && it != '\t' })
                }
            }
        }
    }

    @Test
    fun commandReasonsUseCodepointsAndInvalidReasonsDoNotCreateAudits() {
        JdbcQueueAcceptanceFixture().use { fixture ->
            val jdbc = JdbcTemplate(fixture.dataSource)
            val type = taskType("reason-bounds")
            fixture.registry.register(TaskDefinition(
                type, 1, validate = { require(it.isObject) }, handler = { context, _ -> context.checkpoint() },
            ))
            val manager = entityManager(fixture)
            val clock = fixture.contextQueueClock()
            val store = QueueWorkerStore(
                manager, fixture.transactionManager, clock, fixture.registry,
                fixture.dataSource, fixture.dataDirectory.toString(),
                meterRegistry = io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            )
            runtime(fixture, store).use { worker ->
                val control = QueueControl(manager, fixture.transactionManager, clock, fixture.registry, store, worker, fixture.queue)
                val actorId = fixture.contextUserService().createUser(User(
                    loginId = "queue-reason-${UUID.randomUUID()}", name = "Reason admin",
                    email = "${UUID.randomUUID()}@example.invalid", state = UserState.SITE_ADMIN,
                )).id!!
                val acceptedJob = admit(fixture, type, FAR_FUTURE)
                val acceptedReason = MUSIC_SYMBOL.repeat(300)
                control.cancel(acceptedJob, UUID.randomUUID().toString(), actorId, acceptedReason)
                assertEquals(acceptedReason, jdbc.queryForObject(
                    "SELECT reason FROM queue_admin_audit WHERE job_id = ?", String::class.java, acceptedJob,
                ))
                assertEquals("CANCELLED", fixture.jobRow(acceptedJob)?.status)

                val rejectedJob = admit(fixture, type, FAR_FUTURE)
                val invalidReasons = listOf(MUSIC_SYMBOL.repeat(301), "control\u0001text", "unpaired\uD834")
                for (reason in invalidReasons) {
                    val failure = assertThrows(QueueControlException::class.java) {
                        control.cancel(rejectedJob, UUID.randomUUID().toString(), actorId, reason)
                    }
                    assertEquals("INVALID_REASON", failure.code)
                    assertEquals(0, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM queue_admin_audit WHERE job_id = ?", Int::class.java, rejectedJob,
                    ))
                    assertEquals("QUEUED", fixture.jobRow(rejectedJob)?.status)
                }
            }
        }
    }

    @Test
    fun aSecondArtifactIsRejectedBeforeItsWriterRunsAndTheFirstRemainsPublished() {
        JdbcQueueAcceptanceFixture().use { fixture ->
            val jdbc = JdbcTemplate(fixture.dataSource)
            val secondWriterRan = AtomicBoolean()
            val type = taskType("artifact-bound")
            fixture.registry.register(TaskDefinition(
                type, 1, validate = { require(it.isObject) },
                handler = { context, _ ->
                    val invalidWriterRan = AtomicBoolean()
                    for (path in listOf(
                            "x".repeat(256), MUSIC_SYMBOL.repeat(64) + ".txt",
                            List(17) { MUSIC_SYMBOL.repeat(16) }.joinToString("/") + ".txt",
                            "bad\u0001.txt", "bad\uD834.txt",
                        )) {
                        assertThrows(RuntimeException::class.java) {
                            context.writeArtifact(path) { invalidWriterRan.set(true) }
                        }
                        assertFalse(invalidWriterRan.get())
                    }
                    val writerStarted = CountDownLatch(1)
                    val releaseWriter = CountDownLatch(1)
                    val writer = Executors.newSingleThreadExecutor()
                    var firstWrite: java.util.concurrent.Future<*>? = null
                    try {
                        firstWrite = writer.submit(Runnable {
                            context.writeArtifact("first.txt") { output ->
                                writerStarted.countDown()
                                check(releaseWriter.await(5, TimeUnit.SECONDS))
                                output.write("first artifact".toByteArray())
                            }
                        })
                        check(writerStarted.await(5, TimeUnit.SECONDS))
                        assertThrows(RuntimeException::class.java) {
                            context.writeArtifact("second.txt") {
                                secondWriterRan.set(true)
                                it.write("second artifact".toByteArray())
                            }
                        }
                        assertFalse(secondWriterRan.get())
                    } finally {
                        releaseWriter.countDown()
                        try {
                            firstWrite?.get(5, TimeUnit.SECONDS)
                        } finally {
                            writer.shutdown()
                            check(writer.awaitTermination(5, TimeUnit.SECONDS))
                        }
                    }
                    context.checkpoint()
                },
            ))

            runtime(fixture).use { worker ->
                worker.start()
                val jobId = admit(fixture, type)
                awaitStatus(fixture, jobId, "SUCCEEDED")
                assertFalse(secondWriterRan.get())
                val artifacts = jdbc.queryForList(
                    "SELECT relative_path, storage_path FROM queue_artifact WHERE job_id = ?", jobId,
                )
                assertEquals(1, artifacts.size)
                assertEquals("first.txt", artifacts.single()["relative_path"])
                val published = fixture.dataDirectory.resolve(artifacts.single()["storage_path"].toString())
                assertEquals("first artifact", Files.readString(published))
            }
        }
    }

    @Test
    fun configuredSixAttemptBudgetRemainsRunnable() {
        JdbcQueueAcceptanceFixture().use { fixture ->
            val jdbc = JdbcTemplate(fixture.dataSource)
            val type = taskType("six-attempt-budget")
            fixture.registry.register(TaskDefinition(
                type, 1, validate = { require(it.isObject) },
                handler = { _, _ -> throw RetryableTaskFailure("retry within configured budget") },
                replaySafe = true,
                maxAttempts = 6,
            ))

            runtime(fixture).use { worker ->
                worker.start()
                val jobId = admit(fixture, type)
                for (attemptNo in 1..6) {
                    awaitAttemptCount(fixture, jobId, attemptNo)
                    awaitStatus(fixture, jobId, if (attemptNo == 6) "FAILED" else "RETRY_WAIT")
                    if (attemptNo < 6) {
                        check(jdbc.update(
                            "UPDATE queue_test_clock SET offset_ms = offset_ms + 120000 WHERE singleton_id = 1",
                        ) == 1)
                    }
                }
                assertEquals((1..6).toList(), fixture.attemptRows(jobId).map { it.generationAttemptNo })
                assertEquals(6L, fixture.jobRow(jobId)?.attemptCount)
                assertEquals("RETRY_EXHAUSTED", fixture.jobRow(jobId)?.failureDisposition)
            }
        }
    }

    private fun admit(
        fixture: JdbcQueueAcceptanceFixture,
        type: String,
        dueAt: Instant = Instant.EPOCH,
    ): Long = TransactionTemplate(fixture.transactionManager).execute {
        fixture.queue.enqueue(type, 1, "{}".toByteArray(), dueAt, null, "producer-bounds").jobId
    }!!

    private fun runtime(
        fixture: JdbcQueueAcceptanceFixture,
        store: QueueWorkerStore = QueueWorkerStore(
            entityManager(fixture), fixture.transactionManager, fixture.contextQueueClock(),
            fixture.registry, fixture.dataSource, fixture.dataDirectory.toString(),
            meterRegistry = io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
        ),
    ): QueueWorkerRuntime {
        val clock = fixture.contextQueueClock()
        return QueueWorkerRuntime(
            store, fixture.registry, clock, io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            workers = 1, pollMillis = 20, shutdownGraceMillis = 1000,
            dataDirectory = fixture.dataDirectory.toString(), dbConnectionBudget = 4,
        )
    }

    private fun entityManager(fixture: JdbcQueueAcceptanceFixture): EntityManager =
        SharedEntityManagerCreator.createSharedEntityManager(
            (fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!,
        )

    private fun awaitStatus(fixture: JdbcQueueAcceptanceFixture, jobId: Long, status: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (fixture.jobRow(jobId)?.status != status && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals(status, fixture.jobRow(jobId)?.status)
    }

    private fun awaitAttemptCount(fixture: JdbcQueueAcceptanceFixture, jobId: Long, count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (fixture.attemptRows(jobId).size < count && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals(count, fixture.attemptRows(jobId).size)
    }

    private fun assertWellFormedUtf16(value: String) {
        var index = 0
        while (index < value.length) {
            when {
                Character.isHighSurrogate(value[index]) -> {
                    assertTrue(index + 1 < value.length && Character.isLowSurrogate(value[index + 1]))
                    index += 2
                }
                Character.isLowSurrogate(value[index]) -> throw AssertionError("Unexpected unpaired low surrogate")
                else -> index++
            }
        }
    }

    private fun taskType(suffix: String) = "queue.acceptance.producer.$suffix-${UUID.randomUUID()}"

    private companion object {
        const val MUSIC_SYMBOL = "\uD834\uDD1E"
        val FAR_FUTURE: Instant = Instant.parse("2100-01-01T00:00:00Z")
    }
}
