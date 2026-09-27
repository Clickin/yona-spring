package com.github.yonaprojects.yona.queue

import com.github.yonaprojects.yona.config.SchemaTestDatabase
import jakarta.persistence.LockModeType
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserState
import com.github.yonaprojects.yona.queue.acceptance.JdbcQueueAcceptanceFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class QueueTransactionBoundaryTest {
    @Test
    fun rolledBackCancellationDoesNotSignalTheRunningHandler() {
        JdbcQueueAcceptanceFixture().use { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val definition = TaskDefinition("queue.acceptance.rollback-cancel", 1, {}, handler = { context, _ ->
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                context.checkpoint()
            })
            val registry = TaskRegistry(listOf(definition))
            val entityManager = SharedEntityManagerCreator.createSharedEntityManager(
                (fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!,
            )
            val clock = fixture.contextQueueClock()
            val store = QueueWorkerStore(entityManager, fixture.transactionManager, clock, registry,
                fixture.dataSource, fixture.dataDirectory.toString(), meterRegistry = io.micrometer.core.instrument.simple.SimpleMeterRegistry())
            val actor = fixture.contextUserService().createUser(User(
                loginId = "queue-tx-${UUID.randomUUID()}", name = "Transaction admin",
                email = "${UUID.randomUUID()}@example.invalid", state = UserState.SITE_ADMIN,
            ))
            val receipt = fixture.queue.enqueue(definition.type, 1, "{}".toByteArray(), Instant.EPOCH, null, "boundary")
            QueueWorkerRuntime(store, registry, clock, io.micrometer.core.instrument.simple.SimpleMeterRegistry(), workers = 1, pollMillis = 20,
                shutdownGraceMillis = 1000, dataDirectory = fixture.dataDirectory.toString(),
                dbConnectionBudget = 4).use { runtime ->
                val control = QueueControl(entityManager, fixture.transactionManager, clock, registry, store, runtime, fixture.queue)
                runtime.start()
                try {
                    assertTrue(entered.await(10, TimeUnit.SECONDS), "Real handler did not enter")
                    TransactionTemplate(fixture.transactionManager).executeWithoutResult { transaction ->
                        control.cancel(receipt.jobId, UUID.randomUUID().toString(), actor.id!!)
                        transaction.setRollbackOnly()
                    }
                    assertEquals("RUNNING", fixture.jobRow(receipt.jobId)?.status)
                    assertEquals(0, JdbcTemplate(fixture.dataSource).queryForObject(
                        "SELECT COUNT(*) FROM queue_admin_audit WHERE job_id = ?", Int::class.java, receipt.jobId,
                    ))
                    release.countDown()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (fixture.jobRow(receipt.jobId)?.status == "RUNNING" && System.nanoTime() < deadline) {
                        Thread.sleep(20)
                    }
                    assertEquals("SUCCEEDED", fixture.jobRow(receipt.jobId)?.status)
                } finally {
                    release.countDown()
                }
            }
        }
    }

    @Test
    fun lostCommitAcknowledgementNeverDeletesCommittedArtifactBytes() {
        JdbcQueueAcceptanceFixture().use { fixture ->
            val failNextCommit = AtomicBoolean(false)
            val delegate = fixture.transactionManager
            val transactions = object : PlatformTransactionManager {
                override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = delegate.getTransaction(definition)
                override fun rollback(status: TransactionStatus) = delegate.rollback(status)
                override fun commit(status: TransactionStatus) {
                    delegate.commit(status)
                    if (failNextCommit.compareAndSet(true, false)) throw TransactionSystemException("Injected lost commit acknowledgement")
                }
            }
            val definition = TaskDefinition("queue.acceptance.ambiguous-commit", 1, {}, handler = { _, _ -> })
            val registry = TaskRegistry(listOf(definition))
            val entityManager = SharedEntityManagerCreator.createSharedEntityManager(
                (delegate as JpaTransactionManager).entityManagerFactory!!,
            )
            val store = QueueWorkerStore(entityManager, transactions, fixture.contextQueueClock(), registry,
                fixture.dataSource, fixture.dataDirectory.toString(), meterRegistry = io.micrometer.core.instrument.simple.SimpleMeterRegistry())
            val receipt = fixture.queue.enqueue(definition.type, 1, "{}".toByteArray(), Instant.EPOCH, null, "boundary")
            val candidate = checkNotNull(store.candidate(receipt.jobId))
            val token = checkNotNull(store.claim(candidate, definition,
                registry.decodeAndValidate(definition, candidate.payload), "commit-boundary"))
            val context = TaskContext(token.jobId, token.attemptNo, token.fence, token.ownerInstance, token, store)
            val expected = "committed archive bytes".toByteArray()
            context.writeArtifact("archive.bin") { it.write(expected) }
            val staged = context.closeForHandlerReturn()
            failNextCommit.set(true)
            assertThrows(TransactionSystemException::class.java) { store.complete(token, null, staged) }
            assertEquals("SUCCEEDED", fixture.jobRow(receipt.jobId)?.status)
            val storagePath = JdbcTemplate(fixture.dataSource).queryForObject(
                "SELECT storage_path FROM queue_artifact WHERE job_id = ?", String::class.java, receipt.jobId,
            )!!
            val artifact = fixture.dataDirectory.resolve(storagePath)
            assertTrue(Files.isRegularFile(artifact), "A lost commit acknowledgement deleted committed bytes")
            assertEquals(expected.toList(), Files.readAllBytes(artifact).toList())
        }
    }

    @Test
    fun lockWaitCannotAuthorizeAnExpiredLeaseUsingTransactionStartTime() {
        JdbcQueueAcceptanceFixture().use { fixture ->
            fixture.createAcceptanceMarkerTable()
            val delegate = fixture.transactionManager
            val waiting = CountDownLatch(1)
            val arm = AtomicBoolean(false)
            val jdbc = JdbcTemplate(fixture.dataSource)
            val transactions = object : PlatformTransactionManager {
                override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
                    val status = delegate.getTransaction(definition)
                    if (arm.get()) {
                        if (SchemaTestDatabase.kind == "h2") jdbc.execute("SET LOCK_TIMEOUT 10000")
                        jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", java.sql.Timestamp::class.java)
                        waiting.countDown()
                    }
                    return status
                }
                override fun commit(status: TransactionStatus) = delegate.commit(status)
                override fun rollback(status: TransactionStatus) = delegate.rollback(status)
            }
            val definition = TaskDefinition("queue.acceptance.lock-expiry", 1, {}, handler = { _, _ -> })
            val registry = TaskRegistry(listOf(definition))
            val entityManager = SharedEntityManagerCreator.createSharedEntityManager(
                (delegate as JpaTransactionManager).entityManagerFactory!!,
            )
            val store = QueueWorkerStore(entityManager, transactions, fixture.contextQueueClock(), registry,
                fixture.dataSource, fixture.dataDirectory.toString(), leaseMillis = 2000,
                meterRegistry = io.micrometer.core.instrument.simple.SimpleMeterRegistry())
            val receipt = fixture.queue.enqueue(definition.type, 1, "{}".toByteArray(), Instant.EPOCH, null, "boundary")
            val candidate = checkNotNull(store.candidate(receipt.jobId))
            val token = checkNotNull(store.claim(candidate, definition,
                registry.decodeAndValidate(definition, candidate.payload), "lock-boundary"))
            val marker = UUID.randomUUID().toString()
            val elapsedMillis = AtomicLong(-1)
            val effectTime = AtomicLong(-1)
            val leaseExpiry = jdbc.queryForObject(
                "SELECT lease_expires_at_epoch_ms FROM queue_attempt WHERE job_id = ?",
                Long::class.javaObjectType, receipt.jobId,
            )
            Executors.newSingleThreadExecutor().use { executor ->
                lateinit var result: Future<Throwable?>
                TransactionTemplate(delegate).executeWithoutResult {
                    entityManager.find(QueueJob::class.java, receipt.jobId, LockModeType.PESSIMISTIC_WRITE)
                    arm.set(true)
                    result = executor.submit<Throwable?> {
                        val started = System.nanoTime()
                        try {
                            store.fencedDb(token) { manager ->
                                effectTime.set(fixture.contextQueueClock().leaseNow())
                                manager.createNativeQuery("INSERT INTO queue_acceptance_marker(marker_id) VALUES (?)")
                                    .setParameter(1, marker).executeUpdate()
                            }
                            null
                        } catch (failure: Throwable) {
                            failure
                        } finally {
                            elapsedMillis.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                        }
                    }
                    assertTrue(waiting.await(5, TimeUnit.SECONDS), "Competing transaction did not start")
                    Thread.sleep(2500)
                }
                val outcome = result.get(10, TimeUnit.SECONDS)
                assertTrue(outcome is StaleAttempt,
                    "Expired lease authorized a post-lock write; elapsed=${elapsedMillis.get()}ms, " +
                        "effectTime=${effectTime.get()}, leaseExpiry=$leaseExpiry, outcome=${outcome?.stackTraceToString() ?: "committed"}")
                assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM queue_acceptance_marker WHERE marker_id = ?", Int::class.java, marker,
                ))
            }
        }
    }
}
