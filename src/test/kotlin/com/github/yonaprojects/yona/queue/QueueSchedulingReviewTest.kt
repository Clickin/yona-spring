package com.github.yonaprojects.yona.queue

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.mock.env.MockEnvironment
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class QueueSchedulingReviewTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun executionSlotsBoundBothThreadModesAndSurviveAClaimException(virtual: Boolean) {
        withQueueReviewFixture { fixture ->
            val entered = CountDownLatch(2)
            val release = CountDownLatch(1)
            val running = AtomicInteger()
            val maximum = AtomicInteger()
            val threadModes = ConcurrentLinkedQueue<Boolean>()
            val definition = TaskDefinition("queue.review.slots", 1, {}, handler = { _, _ ->
                threadModes.add(Thread.currentThread().isVirtual)
                maximum.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                entered.countDown()
                try { check(release.await(10, TimeUnit.SECONDS)) } finally { running.decrementAndGet() }
            }, laneLimit = 5)
            fixture.registry.register(definition)
            val delegate = fixture.transactionManager
            val pollTransactions = AtomicInteger()
            val transactions = object : PlatformTransactionManager {
                override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
                    if (Thread.currentThread().name.startsWith("yona-queue-poller") && pollTransactions.incrementAndGet() == 2) {
                        throw IllegalStateException("Injected claim transaction failure")
                    }
                    return delegate.getTransaction(definition)
                }
                override fun commit(status: TransactionStatus) = delegate.commit(status)
                override fun rollback(status: TransactionStatus) = delegate.rollback(status)
            }
            val manager = SharedEntityManagerCreator.createSharedEntityManager((delegate as JpaTransactionManager).entityManagerFactory!!)
            val meters = SimpleMeterRegistry()
            val store = QueueWorkerStore(manager, transactions, fixture.contextQueueClock(), fixture.registry,
                fixture.dataSource, fixture.dataDirectory.toString(), meterRegistry = meters)
            val jobs = (1..5).map { fixture.queue.enqueue(definition.type, 1, "{}".toByteArray(), Instant.EPOCH, null, "review").jobId }
            val runtime = QueueWorkerRuntime(store, fixture.registry, fixture.contextQueueClock(), meters,
                workers = 2, pollMillis = 20, shutdownGraceMillis = 2000,
                dataDirectory = fixture.dataDirectory.toString(), dbConnectionBudget = 4,
                environment = MockEnvironment().withProperty("spring.threads.virtual.enabled", virtual.toString()))
            runtime.use {
                runtime.start()
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    assertEquals(2, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM queue_job WHERE status = 'RUNNING'", Int::class.java))
                    assertEquals(2.0, meters.get("yona.queue.worker.slots.used").gauge().value())
                    release.countDown()
                    await { jobs.all { fixture.jobRow(it)?.status == "SUCCEEDED" } }
                } finally { release.countDown() }
            }
            assertEquals(2, maximum.get())
            assertEquals(List(5) { virtual }, threadModes.toList())
            assertEquals(0.0, meters.get("yona.queue.worker.slots.used").gauge().value())
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun shutdownReturnsTheSlotWhenTheHandlerStopsWithinGrace(virtual: Boolean) {
        withQueueReviewFixture { fixture ->
            val entered = CountDownLatch(1)
            val definition = TaskDefinition("queue.review.shutdown", 1, {}, handler = { context, _ ->
                entered.countDown()
                while (true) {
                    context.checkpoint()
                    Thread.sleep(10)
                }
            })
            fixture.registry.register(definition)
            val manager = SharedEntityManagerCreator.createSharedEntityManager(
                (fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!,
            )
            val meters = SimpleMeterRegistry()
            val store = QueueWorkerStore(manager, fixture.transactionManager, fixture.contextQueueClock(), fixture.registry,
                fixture.dataSource, fixture.dataDirectory.toString(), meterRegistry = meters)
            fixture.queue.enqueue(definition.type, 1, "{}".toByteArray(), Instant.EPOCH, null, "review")
            QueueWorkerRuntime(store, fixture.registry, fixture.contextQueueClock(), meters, workers = 1,
                shutdownGraceMillis = 2000, dataDirectory = fixture.dataDirectory.toString(), dbConnectionBudget = 4,
                environment = MockEnvironment().withProperty("spring.threads.virtual.enabled", virtual.toString())).use { runtime ->
                runtime.start()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
            }
            assertEquals(0.0, meters.get("yona.queue.worker.slots.used").gauge().value())
        }
    }

    @Test
    fun blockedResourcePrefixDoesNotStarveTheNextResourceInOnePoll() {
        withQueueReviewFixture { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val otherEntered = CountDownLatch(1)
            val definition = TaskDefinition("queue.review.resources", 1, {}, { listOf(it.path("resource").asText()) },
                handler = { _, payload ->
                    if (payload.path("resource").asText() == "review:busy") {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    } else otherEntered.countDown()
                }, laneLimit = 2)
            fixture.registry.register(definition)
            repeat(100) { fixture.queue.enqueue(definition.type, 1, "{\"resource\":\"review:busy\"}".toByteArray(), Instant.EPOCH, null, "review") }
            fixture.queue.enqueue(definition.type, 1, "{\"resource\":\"review:free\"}".toByteArray(), Instant.EPOCH, null, "review")
            val manager = SharedEntityManagerCreator.createSharedEntityManager((fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!)
            val meters = SimpleMeterRegistry()
            val store = QueueWorkerStore(manager, fixture.transactionManager, fixture.contextQueueClock(), fixture.registry,
                fixture.dataSource, fixture.dataDirectory.toString(), meterRegistry = meters)
            QueueWorkerRuntime(store, fixture.registry, fixture.contextQueueClock(), meters,
                workers = 2, pollMillis = 1000, dataDirectory = fixture.dataDirectory.toString(),
                shutdownGraceMillis = 1000, dbConnectionBudget = 4).use { runtime ->
                runtime.start()
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    assertTrue(otherEntered.await(900, TimeUnit.MILLISECONDS), "The first poll did not scan past 100 blocked jobs")
                } finally {
                    runtime.stopClaimingNewWork()
                    release.countDown()
                }
            }
        }
    }
    @Test
    fun idleScansBackOffButCommittedLocalWorkWakesThePollerWithinOneHundredMillis() {
        withQueueReviewFixture { fixture ->
            val dueScans = AtomicInteger()
            val recoveryScans = AtomicInteger()
            val dataSource = object : javax.sql.DataSource by fixture.dataSource {
                override fun getConnection(): Connection {
                    val connection = fixture.dataSource.connection
                    return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                        if (method.name == "prepareStatement") {
                            val sql = args?.firstOrNull() as? String ?: ""
                            if (sql.startsWith("SELECT priority, id FROM queue_job")) dueScans.incrementAndGet()
                            if (sql.startsWith("SELECT j.id FROM queue_job")) recoveryScans.incrementAndGet()
                        }
                        try { method.invoke(connection, *(args ?: emptyArray())) }
                        catch (failure: InvocationTargetException) { throw failure.targetException }
                    } as Connection
                }
            }
            val entered = CountDownLatch(1)
            val definition = TaskDefinition("queue.review.wake", 1, {}, handler = { _, _ -> entered.countDown() })
            fixture.registry.register(definition)
            val manager = SharedEntityManagerCreator.createSharedEntityManager((fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!)
            val meters = SimpleMeterRegistry()
            val store = QueueWorkerStore(manager, fixture.transactionManager, fixture.contextQueueClock(), fixture.registry,
                dataSource, fixture.dataDirectory.toString(), meterRegistry = meters)
            val runtime = QueueWorkerRuntime(store, fixture.registry, fixture.contextQueueClock(), meters,
                workers = 1, dataDirectory = fixture.dataDirectory.toString(), dbConnectionBudget = 4)
            AnnotationConfigApplicationContext().use { context ->
                context.environment.propertySources.addFirst(org.springframework.core.env.MapPropertySource(
                    "queue-review", mapOf("yona.queue.workers" to "1", "spring.datasource.hikari.maximum-pool-size" to "4"),
                ))
                context.registerBean(QueueWorkerRuntime::class.java, java.util.function.Supplier { runtime })
                context.refresh()
                // Other acceptance scenarios share the schema; measure only after their due work drains.
                await { store.dueCandidateIds(null, 64).isEmpty() }
                dueScans.set(0)
                Thread.sleep(10_000)
                assertTrue(dueScans.get() in 1..15, "Idle due scans: ${dueScans.get()}")
                assertTrue(recoveryScans.get() in 2..3, "Recovery scans: ${recoveryScans.get()}")
                val queue = Queue(manager, fixture.transactionManager, fixture.contextQueueClock(), fixture.registry,
                    fixture.dataSource, events = context)
                TransactionTemplate(fixture.transactionManager).executeWithoutResult {
                    queue.enqueue(definition.type, 1, "{}".toByteArray(), Instant.EPOCH, null, "review")
                }
                // Work is not eligible before its caller commits; admission time is not wake latency.
                assertTrue(entered.await(100, TimeUnit.MILLISECONDS), "A committed enqueue did not wake the idle poller")
            }
        }
    }


    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition())
    }
}
