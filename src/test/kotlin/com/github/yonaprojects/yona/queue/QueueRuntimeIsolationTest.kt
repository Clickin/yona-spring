package com.github.yonaprojects.yona.queue

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.yonaprojects.yona.queue.acceptance.JdbcQueueAcceptanceFixture
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.SharedEntityManagerCreator
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class QueueRuntimeIsolationTest {
    /** One handler holding its fenced transaction must not starve heartbeats of unrelated jobs on the node. */
    @Test
    fun slowFencedDbDoesNotStallOtherJobsHeartbeats() {
        withQueueReviewFixture { fixture ->
            val release = CountDownLatch(1)
            val peerEntered = CountDownLatch(1)
            val insideFence = CountDownLatch(1)
            val peer = TaskDefinition("queue.review.heartbeat-peer", 1, {}, handler = { _, _ ->
                peerEntered.countDown()
                check(release.await(20, TimeUnit.SECONDS))
            })
            val slow = TaskDefinition("queue.review.slow-fence", 1, {}, handler = { context, _ ->
                context.fencedDb {
                    insideFence.countDown()
                    check(release.await(20, TimeUnit.SECONDS))
                }
            })
            fixture.registry.register(peer)
            fixture.registry.register(slow)
            runtime(fixture, workers = 3).use { runtime ->
                runtime.start()
                try {
                    val peerJob = fixture.queue.enqueue(peer.type, 1, "{}".toByteArray(), Instant.EPOCH, null, "review").jobId
                    assertTrue(peerEntered.await(5, TimeUnit.SECONDS))
                    fixture.queue.enqueue(slow.type, 1, "{}".toByteArray(), Instant.EPOCH, null, "review")
                    assertTrue(insideFence.await(5, TimeUnit.SECONDS))
                    // heartbeat-millis is 100; five missed intervals means the peer is starved.
                    var last = heartbeatAt(fixture, peerJob)
                    var lastChange = System.nanoTime()
                    var worstGapMillis = 0L
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    while (System.nanoTime() < deadline) {
                        Thread.sleep(25)
                        val current = heartbeatAt(fixture, peerJob)
                        if (current != last) {
                            last = current
                            lastChange = System.nanoTime()
                        }
                        worstGapMillis = maxOf(worstGapMillis, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastChange))
                    }
                    assertTrue(worstGapMillis < 500, "Peer heartbeat stalled for ${worstGapMillis}ms behind another job's fencedDb")
                } finally {
                    release.countDown()
                }
            }
        }
    }

    /** The shared poller must keep claiming while a handler is inside fencedDb. */
    @Test
    fun pollerClaimsNewWorkWhileAHandlerIsInsideFencedDb() {
        withQueueReviewFixture { fixture ->
            val release = CountDownLatch(1)
            val insideFence = CountDownLatch(1)
            val quickEntered = CountDownLatch(1)
            val slow = TaskDefinition("queue.review.poller-slow-fence", 1, {}, handler = { context, _ ->
                context.fencedDb {
                    insideFence.countDown()
                    check(release.await(20, TimeUnit.SECONDS))
                }
            })
            val quick = TaskDefinition("queue.review.poller-quick", 1, {}, handler = { _, _ -> quickEntered.countDown() })
            fixture.registry.register(slow)
            fixture.registry.register(quick)
            runtime(fixture, workers = 3).use { runtime ->
                runtime.start()
                try {
                    fixture.queue.enqueue(slow.type, 1, "{}".toByteArray(), Instant.EPOCH, null, "review")
                    assertTrue(insideFence.await(5, TimeUnit.SECONDS))
                    fixture.queue.enqueue(quick.type, 1, "{}".toByteArray(), Instant.EPOCH, null, "review")
                    assertTrue(quickEntered.await(1, TimeUnit.SECONDS), "Poller was blocked by another handler's fencedDb")
                } finally {
                    release.countDown()
                }
            }
        }
    }

    /** fencedDb is documented as short; its transaction must time out at lease-millis / 4 instead of trusting handlers. */
    @Test
    fun fencedDbTransactionTimesOutAtAQuarterOfTheLease() {
        withQueueReviewFixture { fixture ->
            val outcome = AtomicReference<Throwable?>()
            val finished = CountDownLatch(1)
            val slow = TaskDefinition("queue.review.fence-timeout", 1, {}, handler = { context, _ ->
                try {
                    context.fencedDb { manager ->
                        Thread.sleep(1_500)
                        manager.createNativeQuery("SELECT COUNT(*) FROM queue_meta").resultList
                    }
                } catch (failure: Throwable) {
                    outcome.set(failure)
                } finally {
                    finished.countDown()
                }
            })
            fixture.registry.register(slow)
            // lease 4000 ms -> 1 s transaction timeout; the block runs 1.5 s.
            runtime(fixture, workers = 1, leaseMillis = 4_000, heartbeatMillis = 1_000).use { runtime ->
                runtime.start()
                fixture.queue.enqueue(slow.type, 1, "{}".toByteArray(), Instant.EPOCH, null, "review")
                assertTrue(finished.await(10, TimeUnit.SECONDS))
                assertNotNull(outcome.get(), "A fencedDb block longer than lease-millis / 4 must fail")
            }
        }
    }

    /** Clock trust must be established before workers start and outlive them during shutdown. */
    @Test
    fun clockStartsBeforeAndStopsAfterTheWorkerRuntime() {
        withQueueReviewFixture { fixture ->
            runtime(fixture, workers = 1).use { runtime ->
                val clock = QueueClock(fixture.dataSource)
                assertTrue(clock.phase < runtime.phase, "QueueClock phase ${clock.phase} must be lower than runtime phase ${runtime.phase}")
            }
        }
    }

    /** A continuing outage is logged once with detail, not once per 20 ms poll. */
    @Test
    fun untrustedClockDoesNotFloodPollerWarnings() {
        withQueueReviewFixture { fixture ->
            val logger = LoggerFactory.getLogger(QueueWorkerRuntime::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            try {
                // Never verified, therefore permanently untrusted.
                val untrusted = QueueClock(fixture.dataSource)
                val manager = SharedEntityManagerCreator.createSharedEntityManager(
                    (fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!,
                )
                val meters = SimpleMeterRegistry()
                val store = QueueWorkerStore(manager, fixture.transactionManager, untrusted, fixture.registry,
                    fixture.dataSource, fixture.dataDirectory.toString(), meterRegistry = meters)
                QueueWorkerRuntime(store, fixture.registry, untrusted, meters, workers = 1, pollMillis = 20,
                    dataDirectory = fixture.dataDirectory.toString(), dbConnectionBudget = 4).use { runtime ->
                    runtime.start()
                    Thread.sleep(1_500)
                }
                val warnings = appender.list.filter { it.level == Level.WARN }
                assertTrue(warnings.size <= 2, "Expected at most one transition warning, got ${warnings.size}")
            } finally {
                logger.detachAppender(appender)
            }
        }
    }

    private fun heartbeatAt(fixture: JdbcQueueAcceptanceFixture, jobId: Long): Long? = fixture.jdbc.queryForObject(
        "SELECT a.heartbeat_at_epoch_ms FROM queue_attempt a JOIN queue_job j ON a.job_id = j.id " +
            "AND a.attempt_no = j.active_attempt_no WHERE j.id = ?",
        Long::class.javaObjectType, jobId,
    )

    private fun runtime(
        fixture: JdbcQueueAcceptanceFixture,
        workers: Int,
        leaseMillis: Long = 10_000,
        heartbeatMillis: Long = 100,
    ): QueueWorkerRuntime {
        val manager = SharedEntityManagerCreator.createSharedEntityManager(
            (fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!,
        )
        val meters = SimpleMeterRegistry()
        val clock = fixture.contextQueueClock()
        val store = QueueWorkerStore(manager, fixture.transactionManager, clock, fixture.registry,
            fixture.dataSource, fixture.dataDirectory.toString(), leaseMillis = leaseMillis, meterRegistry = meters)
        return QueueWorkerRuntime(store, fixture.registry, clock, meters, workers = workers, pollMillis = 20,
            leaseMillis = leaseMillis, heartbeatMillis = heartbeatMillis, shutdownGraceMillis = 5_000,
            dataDirectory = fixture.dataDirectory.toString(), dbConnectionBudget = 8)
    }
}
