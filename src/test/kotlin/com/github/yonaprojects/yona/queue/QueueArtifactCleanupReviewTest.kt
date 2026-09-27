package com.github.yonaprojects.yona.queue

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class QueueArtifactCleanupReviewTest {
    @Test
    fun rolledBackArtifactPublicationIsQuarantinedWithoutFollowingSymlinks() {
        withQueueReviewFixture { fixture ->
            val rejectCommit = AtomicBoolean()
            val delegate = fixture.transactionManager
            val transactions = object : PlatformTransactionManager {
                override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = delegate.getTransaction(definition)
                override fun rollback(status: TransactionStatus) = delegate.rollback(status)
                override fun commit(status: TransactionStatus) {
                    if (rejectCommit.compareAndSet(true, false)) {
                        delegate.rollback(status)
                        throw StaleAttempt()
                    }
                    delegate.commit(status)
                }
            }
            val definition = TaskDefinition("queue.review.orphan", 1, {}, handler = { _, _ -> })
            fixture.registry.register(definition)
            val manager = SharedEntityManagerCreator.createSharedEntityManager((delegate as JpaTransactionManager).entityManagerFactory!!)
            val store = QueueWorkerStore(manager, transactions, fixture.contextQueueClock(), fixture.registry,
                fixture.dataSource, fixture.dataDirectory.toString(), meterRegistry = SimpleMeterRegistry())
            val job = fixture.queue.enqueue(definition.type, 1, "{}".toByteArray(), Instant.EPOCH, null, "review").jobId
            val candidate = checkNotNull(store.candidate(job))
            val token = checkNotNull(store.claim(candidate, definition, fixture.registry.decodeAndValidate(definition, candidate.payload), "orphan-test"))
            val context = TaskContext(job, token.attemptNo, token.fence, token.ownerInstance, token, store)
            context.writeArtifact("result.bin") { it.write(byteArrayOf(3, 2, 1)) }
            val staged = context.closeForHandlerReturn()
            rejectCommit.set(true)
            assertThrows(StaleAttempt::class.java) { store.complete(token, null, staged) }
            val outside = Files.createTempDirectory("queue-cleanup-outside-")
            try {
                val sentinel = Files.writeString(outside.resolve("keep.txt"), "keep")
                Files.createSymbolicLink(fixture.dataDirectory.resolve("staging/999999"), outside)
                val staleStaging = Files.createDirectories(fixture.dataDirectory.resolve("staging/999998/1-1"))
                Files.createSymbolicLink(staleStaging.resolve("outside"), outside)
                val runningStaging = Files.writeString(store.stagingDirectory(token).resolve("running.part"), "running")
                store.cleanupOrphans()
                assertTrue(Files.exists(sentinel))
                assertTrue(Files.exists(runningStaging), "A live attempt's staging was deleted")
                assertFalse(Files.exists(staleStaging))
                val quarantined = Files.walk(fixture.dataDirectory.resolve("artifacts-orphaned")).use { paths ->
                    paths.filter { Files.isRegularFile(it) }.toList()
                }
                assertEquals(1, quarantined.size)
                assertArrayEquals(byteArrayOf(3, 2, 1), Files.readAllBytes(quarantined.single()))
                assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM queue_artifact WHERE job_id = ?", Int::class.java, job))
            } finally {
                Files.deleteIfExists(outside.resolve("keep.txt"))
                Files.deleteIfExists(outside)
            }
        }
    }

    @Test
    fun startupCleanupCannotQuarantineAnArtifactWhileAnotherNodeCommitsIt() {
        withQueueReviewFixture { fixture ->
            val publishing = CountDownLatch(1)
            val allowCommit = CountDownLatch(1)
            val delayCommit = AtomicBoolean()
            val delegate = fixture.transactionManager
            val transactions = object : PlatformTransactionManager {
                override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = delegate.getTransaction(definition)
                override fun rollback(status: TransactionStatus) = delegate.rollback(status)
                override fun commit(status: TransactionStatus) {
                    if (delayCommit.compareAndSet(true, false)) {
                        publishing.countDown()
                        check(allowCommit.await(10, TimeUnit.SECONDS))
                    }
                    delegate.commit(status)
                }
            }
            val definition = TaskDefinition("queue.review.inflight-artifact", 1, {}, handler = { _, _ -> })
            fixture.registry.register(definition)
            val manager = SharedEntityManagerCreator.createSharedEntityManager((delegate as JpaTransactionManager).entityManagerFactory!!)
            val store = QueueWorkerStore(manager, transactions, fixture.contextQueueClock(), fixture.registry,
                fixture.dataSource, fixture.dataDirectory.toString(), meterRegistry = SimpleMeterRegistry())
            val job = fixture.queue.enqueue(definition.type, 1, "{}".toByteArray(), Instant.EPOCH, null, "review").jobId
            val candidate = checkNotNull(store.candidate(job))
            val token = checkNotNull(store.claim(candidate, definition, fixture.registry.decodeAndValidate(definition, candidate.payload), "publishing-node"))
            val context = TaskContext(job, token.attemptNo, token.fence, token.ownerInstance, token, store)
            context.writeArtifact("committed.bin") { it.write(byteArrayOf(7)) }
            val staged = context.closeForHandlerReturn()
            delayCommit.set(true)
            Executors.newFixedThreadPool(2).use { executor ->
                val completion = executor.submit<Boolean> { store.complete(token, null, staged) }
                try {
                    assertTrue(publishing.await(5, TimeUnit.SECONDS))
                    store.deleteStaging(token)
                    val cleanupStarted = CountDownLatch(1)
                    val cleanup = executor.submit { cleanupStarted.countDown(); store.cleanupOrphans() }
                    assertTrue(cleanupStarted.await(5, TimeUnit.SECONDS))
                    assertThrows(java.util.concurrent.TimeoutException::class.java) {
                        cleanup.get(200, TimeUnit.MILLISECONDS)
                    }
                    allowCommit.countDown()
                    assertTrue(completion.get(5, TimeUnit.SECONDS))
                    cleanup.get(5, TimeUnit.SECONDS)
                } finally { allowCommit.countDown() }
            }
            val path = fixture.jdbc.queryForObject("SELECT storage_path FROM queue_artifact WHERE job_id = ?", String::class.java, job)!!
            assertArrayEquals(byteArrayOf(7), Files.readAllBytes(fixture.dataDirectory.resolve(path)))
            assertFalse(Files.exists(fixture.dataDirectory.resolve("artifacts-orphaned")))
        }
    }

    @Test
    fun startupCleanupStopsAfterAtMostOneThousandDirectories() {
        withQueueReviewFixture { fixture ->
            val staging = fixture.dataDirectory.resolve("staging/999997")
            repeat(1001) { Files.createDirectories(staging.resolve("${it + 1}-1")) }
            val manager = SharedEntityManagerCreator.createSharedEntityManager(
                (fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!,
            )
            val store = QueueWorkerStore(manager, fixture.transactionManager, fixture.contextQueueClock(), fixture.registry,
                fixture.dataSource, fixture.dataDirectory.toString(), meterRegistry = SimpleMeterRegistry())
            store.cleanupOrphans()
            val remaining = Files.list(staging).use { it.count() }
            assertTrue(remaining in 1L..1000L, "Startup must clean some orphans but leave work beyond its 1000-directory bound")
        }
    }

    @Test
    fun partialStagingCleanupAdvancesPastARetainedRunningAttempt() {
        withQueueReviewFixture { fixture ->
            val definition = TaskDefinition("queue.review.cleanup-running-prefix", 1, {}, handler = { _, _ -> })
            fixture.registry.register(definition)
            val manager = SharedEntityManagerCreator.createSharedEntityManager(
                (fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!,
            )
            val store = QueueWorkerStore(manager, fixture.transactionManager, fixture.contextQueueClock(), fixture.registry,
                fixture.dataSource, fixture.dataDirectory.toString(), meterRegistry = SimpleMeterRegistry())
            val job = fixture.queue.enqueue(definition.type, 1, "{}".toByteArray(), Instant.EPOCH, null, "review").jobId
            val candidate = checkNotNull(store.candidate(job))
            val token = checkNotNull(store.claim(candidate, definition, fixture.registry.decodeAndValidate(definition, candidate.payload), "cleanup-node"))
            val live = Files.createDirectories(store.stagingDirectory(token))
            val orphan = Files.createDirectories(live.parent.resolve("${token.attemptNo + 1}-${token.fence + 1}"))
            val sentinel = Files.writeString(live.resolve("running.part"), "keep")

            repeat(3) { store.cleanupOrphans(limit = 1) }

            assertEquals("keep", Files.readString(sentinel))
            assertFalse(Files.exists(orphan), "A retained RUNNING prefix must not starve later staging")
        }
    }

    @Test
    fun cleanupResumesFromItsPersistedNumericJobCursor() {
        withQueueReviewFixture { fixture ->
            val manager = SharedEntityManagerCreator.createSharedEntityManager(
                (fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!,
            )
            fun store() = QueueWorkerStore(manager, fixture.transactionManager, fixture.contextQueueClock(), fixture.registry,
                fixture.dataSource, fixture.dataDirectory.toString(), meterRegistry = SimpleMeterRegistry())
            for (jobId in listOf(999_999_999L, 1_000_000_000L)) {
                val directory = Files.createDirectories(fixture.dataDirectory.resolve("artifacts/$jobId/1-1/result"))
                Files.writeString(directory.resolve("orphan.bin"), jobId.toString())
            }

            store().cleanupOrphans(limit = 1)
            assertFalse(Files.exists(fixture.dataDirectory.resolve("artifacts/999999999")))
            assertTrue(Files.exists(fixture.dataDirectory.resolve("artifacts/1000000000")))
            assertEquals("999999999", Files.readString(fixture.dataDirectory.resolve("cleanup.cursor")))

            store().cleanupOrphans(limit = 1)
            assertFalse(Files.exists(fixture.dataDirectory.resolve("artifacts/1000000000")))
            assertEquals("1000000000", Files.readString(
                fixture.dataDirectory.resolve("artifacts-orphaned/1000000000/1-1/result/orphan.bin"),
            ))
            assertEquals("0", Files.readString(fixture.dataDirectory.resolve("cleanup.cursor")))
        }
    }
}
