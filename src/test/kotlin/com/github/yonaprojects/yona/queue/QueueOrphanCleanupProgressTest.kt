package com.github.yonaprojects.yona.queue

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.SharedEntityManagerCreator
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

class QueueOrphanCleanupProgressTest {
    /** Staging for a finished job must not leave an empty per-job directory behind. */
    @Test
    fun finishedArtifactJobLeavesNoStagingDirectory() {
        withQueueReviewFixture { fixture ->
            val definition = TaskDefinition("queue.review.staging-parent", 1, {}, handler = { _, _ -> })
            fixture.registry.register(definition)
            val store = store(fixture)
            val job = fixture.queue.enqueue(definition.type, 1, "{}".toByteArray(), Instant.EPOCH, null, "review").jobId
            val candidate = checkNotNull(store.candidate(job))
            val token = checkNotNull(store.claim(candidate, definition, fixture.registry.decodeAndValidate(definition, candidate.payload), "staging-node"))
            val context = TaskContext(job, token.attemptNo, token.fence, token.ownerInstance, token, store)
            context.writeArtifact("result.bin") { it.write(byteArrayOf(1)) }
            assertTrue(store.complete(token, null, context.closeForHandlerReturn()))
            store.deleteStaging(token)
            assertFalse(Files.exists(fixture.dataDirectory.resolve("staging/$job")), "Empty staging/<jobId> remained")
        }
    }

    /** Repeated cleanup runs must make progress past the first batch and leave no empty directories. */
    @Test
    fun repeatedCleanupEventuallyQuarantinesEveryOrphanBeyondTheFirstBatch() {
        withQueueReviewFixture { fixture ->
            val base = 880_000_000L
            val count = 1_200
            repeat(count) { index ->
                val directory = Files.createDirectories(
                    fixture.dataDirectory.resolve("artifacts/${base + index}/1-1/${UUID.randomUUID()}"),
                )
                Files.write(directory.resolve("orphan.bin"), byteArrayOf(index.toByte()))
            }
            val store = store(fixture)
            repeat(30) { store.cleanupOrphans() }
            val remainingJobDirectories = (0 until count).count { Files.exists(fixture.dataDirectory.resolve("artifacts/${base + it}")) }
            assertEquals(0, remainingJobDirectories, "Cleanup stopped progressing or left empty directories")
            val quarantined = Files.walk(fixture.dataDirectory.resolve("artifacts-orphaned")).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString() == "orphan.bin" }.count()
            }
            assertEquals(count.toLong(), quarantined)
        }
    }

    private fun store(fixture: com.github.yonaprojects.yona.queue.acceptance.JdbcQueueAcceptanceFixture): QueueWorkerStore {
        val manager = SharedEntityManagerCreator.createSharedEntityManager(
            (fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!,
        )
        return QueueWorkerStore(manager, fixture.transactionManager, fixture.contextQueueClock(), fixture.registry,
            fixture.dataSource, fixture.dataDirectory.toString(), meterRegistry = SimpleMeterRegistry())
    }
}
