package com.github.yonaprojects.yona.queue

import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserState
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.SharedEntityManagerCreator
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class QueueRuntimeReviewTest {
    @Test
    fun cancellingAFailingHandlerKeepsItsFailureAndReleasesResourcesAndStagingImmediately() {
        withQueueReviewFixture { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val definition = TaskDefinition("queue.review.cancel-failure", 1, {}, { listOf("review:cancel") },
                handler = { context, _ ->
                    context.writeArtifact("failed.bin") { it.write(byteArrayOf(1, 2, 3)) }
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    throw PermanentTaskFailure("Failure after cancel", "AFTER_CANCEL")
                })
            fixture.registry.register(definition)
            val manager = SharedEntityManagerCreator.createSharedEntityManager(
                (fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!,
            )
            val clock = fixture.contextQueueClock()
            val meters = SimpleMeterRegistry()
            val store = QueueWorkerStore(manager, fixture.transactionManager, clock, fixture.registry,
                fixture.dataSource, fixture.dataDirectory.toString(), meterRegistry = meters)
            val actor = fixture.contextUserService().createUser(User(
                loginId = "queue-review-${UUID.randomUUID()}", name = "Queue admin",
                email = "${UUID.randomUUID()}@example.invalid", state = UserState.SITE_ADMIN,
            ))
            val job = fixture.queue.enqueue(definition.type, 1, "{}".toByteArray(), Instant.EPOCH, null, "review").jobId
            QueueWorkerRuntime(store, fixture.registry, clock, meters, workers = 1, pollMillis = 20,
                shutdownGraceMillis = 1000, dataDirectory = fixture.dataDirectory.toString(),
                dbConnectionBudget = 4).use { runtime ->
                val control = QueueControl(manager, fixture.transactionManager, clock, fixture.registry, store, runtime, fixture.queue)
                runtime.start()
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    control.cancel(job, UUID.randomUUID().toString(), actor.id!!)
                    release.countDown()
                    await { fixture.jobRow(job)?.status == "CANCELLED" && meters.get("yona.queue.worker.slots.used").gauge().value() == 0.0 }
                    assertEquals("PERMANENT_FAILURE", fixture.attemptRows(job).single().status)
                    assertEquals("AFTER_CANCEL", fixture.jdbc.queryForObject("SELECT error_code FROM queue_job WHERE id = ?", String::class.java, job))
                    assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM queue_resource_lock WHERE current_job_id = ?", Int::class.java, job))
                    assertFalse(Files.exists(fixture.dataDirectory.resolve("staging/$job/1-1")))
                    assertTrue(Files.isDirectory(fixture.dataDirectory.resolve("resource-guards")))
                } finally {
                    release.countDown()
                }
            }
        }
    }

    @Test
    fun unsupportedPayloadAndResourceChangesRetainSpecificErrorCodes() {
        withQueueReviewFixture { fixture ->
            val payloadType = "queue.review.invalid-payload"
            val resourceType = "queue.review.resource-mismatch"
            fixture.registry.register(TaskDefinition(payloadType, 1, {}, handler = { _, _ -> }))
            fixture.registry.register(TaskDefinition(resourceType, 1, {}, { listOf("review:old") }, handler = { _, _ -> }))
            val payloadJob = fixture.queue.enqueue(payloadType, 1, "{}".toByteArray(), Instant.EPOCH, null, "review").jobId
            val resourceJob = fixture.queue.enqueue(resourceType, 1, "{}".toByteArray(), Instant.EPOCH, null, "review").jobId
            val registry = TaskRegistry(listOf(
                TaskDefinition(payloadType, 1, { error("Not accepted by this node") }, handler = { _, _ -> }),
                TaskDefinition(resourceType, 1, {}, { listOf("review:new") }, handler = { _, _ -> }),
            ))
            val manager = SharedEntityManagerCreator.createSharedEntityManager(
                (fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!,
            )
            val meters = SimpleMeterRegistry()
            val store = QueueWorkerStore(manager, fixture.transactionManager, fixture.contextQueueClock(), registry,
                fixture.dataSource, fixture.dataDirectory.toString(), meterRegistry = meters)
            QueueWorkerRuntime(store, registry, fixture.contextQueueClock(), meters, workers = 1, pollMillis = 20,
                dataDirectory = fixture.dataDirectory.toString(), dbConnectionBudget = 4).use { runtime ->
                runtime.start()
                await { listOf(payloadJob, resourceJob).all { fixture.jobRow(it)?.status == "BLOCKED_UNSUPPORTED" } }
                assertEquals("INVALID_PAYLOAD", fixture.jdbc.queryForObject("SELECT error_code FROM queue_job WHERE id = ?", String::class.java, payloadJob))
                assertEquals("RESOURCE_MISMATCH", fixture.jdbc.queryForObject("SELECT error_code FROM queue_job WHERE id = ?", String::class.java, resourceJob))
                assertTrue(fixture.attemptRows(payloadJob).isEmpty())
                assertTrue(fixture.attemptRows(resourceJob).isEmpty())
            }
        }
    }

    @Test
    fun cleanupWaitingForStagedFencedWorkDoesNotBlockNewClaims() {
        withQueueReviewFixture { fixture ->
            // Handler, heartbeat and cleanup each hold a connection; admission needs two more.
            fixture.dataSource.unwrap(com.zaxxer.hikari.HikariDataSource::class.java).maximumPoolSize = 8
            val insideFence = CountDownLatch(1)
            val release = CountDownLatch(1)
            val quickEntered = CountDownLatch(1)
            val slow = TaskDefinition("queue.review.cleanup-slow-fence", 1, {}, handler = { context, _ ->
                context.writeArtifact("result.bin") { it.write(byteArrayOf(1)) }
                context.fencedDb {
                    insideFence.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            })
            val quick = TaskDefinition("queue.review.cleanup-quick", 1, {}, handler = { _, _ -> quickEntered.countDown() })
            fixture.registry.register(slow)
            fixture.registry.register(quick)
            val manager = SharedEntityManagerCreator.createSharedEntityManager(
                (fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!,
            )
            val meters = SimpleMeterRegistry()
            val clock = fixture.contextQueueClock()
            val store = QueueWorkerStore(manager, fixture.transactionManager, clock, fixture.registry,
                fixture.dataSource, fixture.dataDirectory.toString(), leaseMillis = 20_000, meterRegistry = meters)
            QueueWorkerRuntime(store, fixture.registry, clock, meters, workers = 2, pollMillis = 20,
                leaseMillis = 20_000, heartbeatMillis = 100, configuredRecoveryPollMillis = 50,
                dataDirectory = fixture.dataDirectory.toString(), dbConnectionBudget = 8).use { runtime ->
                runtime.start()
                try {
                    fixture.queue.enqueue(slow.type, 1, "{}".toByteArray(), Instant.EPOCH, null, "review")
                    assertTrue(insideFence.await(5, TimeUnit.SECONDS))
                    // Cross several cleanup ticks while the staged job's row remains locked.
                    Thread.sleep(500)
                    fixture.queue.enqueue(quick.type, 1, "{}".toByteArray(), Instant.EPOCH, null, "review")
                    runtime.wake()
                    assertTrue(quickEntered.await(1, TimeUnit.SECONDS), "Orphan cleanup blocked the claim poller")
                } finally {
                    release.countDown()
                }
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition())
    }
}
