package com.github.yonaprojects.yona.queue

import com.github.yonaprojects.yona.queue.acceptance.JdbcQueueAcceptanceFixture
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.UUID

class QueueRetentionTest {
    private class Harness(
        val fixture: JdbcQueueAcceptanceFixture,
        terminalDays: Int = 1,
        auditDays: Int = 1,
        batch: Int = 500,
    ) {
        val meters = SimpleMeterRegistry()
        val clock = fixture.contextQueueClock()
        val definition = TaskDefinition(
            "queue.review.retention", 1, {},
            resourceKeys = { node -> node.get("resource")?.textValue()?.let { listOf(it) } ?: emptyList() },
            handler = { _, _ -> },
        )
        val manager = SharedEntityManagerCreator.createSharedEntityManager(
            (fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!,
        )
        val store: QueueWorkerStore
        val retention: QueueRetention
        val jdbc get() = fixture.jdbc
        val root: Path get() = fixture.dataDirectory.toRealPath()

        init {
            if (fixture.registry.find(definition.type, 1) == null) fixture.registry.register(definition)
            store = QueueWorkerStore(manager, fixture.transactionManager, clock, fixture.registry,
                fixture.dataSource, fixture.dataDirectory.toString(), meterRegistry = meters)
            retention = QueueRetention(manager, fixture.transactionManager, clock, store, meters,
                terminalDays, auditDays, batch)
        }

        fun pass(): QueueRetentionResult {
            clock.verifySynchronization()
            return retention.runPass()
        }

        fun enqueue(resource: String? = null, key: String? = null): Long = fixture.queue.enqueue(
            definition.type, 1, (if (resource == null) "{}" else """{"resource":"$resource"}""").toByteArray(),
            Instant.EPOCH, key, "review",
        ).jobId

        fun claim(job: Long): QueueAttemptToken {
            val candidate = checkNotNull(store.candidate(job))
            return checkNotNull(store.claim(candidate, definition, fixture.registry.decodeAndValidate(definition, candidate.payload), "retention-node"))
        }

        /** Runs the real claim/complete path; the result is a terminal job that has not been aged yet. */
        fun finish(resource: String? = null, key: String? = null, artifact: Boolean = false, failure: Throwable? = null): Long {
            val job = enqueue(resource, key)
            val token = claim(job)
            val context = TaskContext(job, token.attemptNo, token.fence, token.ownerInstance, token, store)
            if (artifact) context.writeArtifact("out/result.bin") { it.write(byteArrayOf(1, 2, 3)) }
            assertTrue(store.complete(token, failure, context.closeForHandlerReturn()))
            store.releaseAfterHandlerReturn(token)
            store.deleteStaging(token)
            return job
        }

        fun cancelled(): Long = enqueue().also { job ->
            jdbc.update("UPDATE queue_job SET status = 'CANCELLED', finished_at_epoch_ms = ? WHERE id = ?", clock.now(), job)
        }

        fun age(job: Long, days: Double) {
            jdbc.update(
                "UPDATE queue_job SET finished_at_epoch_ms = ? WHERE id = ?",
                clock.now() - (days * 86_400_000).toLong(), job,
            )
        }

        fun status(job: Long): String? = jdbc.query("SELECT status FROM queue_job WHERE id = ?", { r, _ -> r.getString(1) }, job).singleOrNull()

        fun count(table: String, job: Long, column: String = "job_id"): Int =
            jdbc.queryForObject("SELECT COUNT(*) FROM $table WHERE $column = ?", Int::class.java, job)!!

        fun generation(): Long = jdbc.queryForObject(
            "SELECT counter_value FROM queue_meta WHERE counter_name = 'change-generation'", Long::class.javaObjectType,
        )!!

        fun failedCounter(): Long = jdbc.queryForObject(
            "SELECT counter_value FROM queue_meta WHERE counter_name = 'failed-jobs'", Long::class.javaObjectType,
        )!!

        fun artifactPath(job: Long): Path = root.resolve(jdbc.queryForObject(
            "SELECT storage_path FROM queue_artifact WHERE job_id = ?", String::class.java, job,
        )!!)

        // JPA insert: "action" is a reserved word on some databases and Hibernate quotes it.
        fun audit(job: Long, ageDays: Double): String = UUID.randomUUID().toString().also { id ->
            TransactionTemplate(fixture.transactionManager).executeWithoutResult {
                manager.persist(QueueAdminAudit(
                    commandId = id, commandHash = "0".repeat(64), jobId = job, actorUserId = 1L, action = "CANCEL",
                    priorStatus = QueueStatus.QUEUED, newStatus = QueueStatus.CANCELLED,
                    createdAt = clock.now() - (ageDays * 86_400_000).toLong(),
                ))
            }
        }

        fun auditExists(id: String) =
            jdbc.queryForObject("SELECT COUNT(*) FROM queue_admin_audit WHERE command_id = ?", Int::class.java, id)!! == 1

        fun lockExists(key: String) =
            jdbc.queryForObject("SELECT COUNT(*) FROM queue_resource_lock WHERE resource_key = ?", Int::class.java, key)!! == 1

        fun counter(kind: String) = meters.counter("yona.queue.retention.deleted", "kind", kind).count()
    }

    @Test
    fun oldSucceededAndCancelledJobsAreDeletedWithChildRowsFilesAndEmptyDirectories() {
        withQueueReviewFixture { fixture ->
            val h = Harness(fixture)
            val resource = "retention:${UUID.randomUUID()}"
            val succeeded = h.finish(resource = resource, key = "retention-${UUID.randomUUID()}", artifact = true)
            val cancelled = h.cancelled()
            val file = h.artifactPath(succeeded)
            assertTrue(Files.exists(file))
            assertEquals(1, h.count("queue_attempt", succeeded))
            assertEquals(1, h.count("queue_job_resource", succeeded))
            assertEquals(1, h.count("queue_idempotency_key", succeeded))
            h.age(succeeded, 2.0)
            h.age(cancelled, 2.0)
            val failedBefore = h.failedCounter()
            val generationBefore = h.generation()

            val result = h.pass()

            assertTrue(result.jobs >= 2)
            for (job in listOf(succeeded, cancelled)) {
                assertNull(h.status(job))
                for (table in listOf("queue_artifact", "queue_attempt", "queue_job_resource", "queue_idempotency_key")) {
                    assertEquals(0, h.count(table, job), "$table rows for job $job remained")
                }
            }
            assertFalse(Files.exists(file))
            assertFalse(Files.exists(h.root.resolve("artifacts/$succeeded")), "Empty artifact directories remained")
            assertTrue(Files.isDirectory(h.root.resolve("artifacts")))
            assertTrue(h.generation() > generationBefore, "change-generation must advance so admin SSE refreshes")
            assertEquals(failedBefore, h.failedCounter())
            assertEquals(result.jobs.toDouble(), h.counter("job"))
        }
    }

    @Test
    fun recentTerminalJobsAreKept() {
        withQueueReviewFixture { fixture ->
            val h = Harness(fixture)
            val fresh = h.finish()
            val nearlyOld = h.finish()
            h.age(nearlyOld, 0.9)
            val cancelled = h.cancelled()
            h.pass()
            assertEquals("SUCCEEDED", h.status(fresh))
            assertEquals("SUCCEEDED", h.status(nearlyOld))
            assertEquals("CANCELLED", h.status(cancelled))
        }
    }

    @Test
    fun attentionAndNonTerminalStatesAreNeverDeletedAndFailedCounterIsUnchanged() {
        withQueueReviewFixture { fixture ->
            val h = Harness(fixture)
            val failed = h.finish(failure = PermanentTaskFailure("boom"))
            val recovery = h.finish(failure = RecoveryRequiredTaskFailure("check"))
            val blocked = h.enqueue()
            h.jdbc.update("UPDATE queue_job SET status = 'BLOCKED_UNSUPPORTED', finished_at_epoch_ms = 1 WHERE id = ?", blocked)
            val queued = h.enqueue()
            val running = h.enqueue()
            h.claim(running)
            val succeeded = h.finish()
            val all = listOf(failed, recovery, blocked, queued, running)
            val expected = all.associateWith { h.status(it) }
            assertEquals("FAILED", expected[failed])
            assertEquals("RECOVERY_REQUIRED", expected[recovery])
            for (job in all + succeeded) h.age(job, 400.0)
            val failedBefore = h.failedCounter()

            h.pass()

            assertNull(h.status(succeeded))
            for ((job, status) in expected) assertEquals(status, h.status(job), "job $job must be kept")
            assertEquals(failedBefore, h.failedCounter())
        }
    }

    @Test
    fun zeroDaysDisableJobAndAuditDeletion() {
        withQueueReviewFixture { fixture ->
            val h = Harness(fixture, terminalDays = 0, auditDays = 0)
            val job = h.finish()
            h.age(job, 5000.0)
            val audit = h.audit(job, 5000.0)
            try {
                val result = h.pass()
                assertEquals(0, result.jobs)
                assertEquals(0, result.audit)
                assertEquals("SUCCEEDED", h.status(job))
                assertTrue(h.auditExists(audit))
            } finally {
                h.jdbc.update("DELETE FROM queue_admin_audit WHERE command_id = ?", audit)
            }
        }
    }

    @Test
    fun batchLimitsJobsAuditRowsAndResourceLocksPerPass() {
        withQueueReviewFixture { fixture ->
            val h = Harness(fixture, batch = 2)
            val jobs = List(5) { h.finish() }
            jobs.forEach { h.age(it, 3.0) }
            h.pass()
            assertEquals(3, jobs.count { h.status(it) != null })
            h.pass()
            h.pass()
            assertEquals(0, jobs.count { h.status(it) != null })

            val anchor = h.finish()
            val audits = List(5) { h.audit(anchor, 10.0) }
            val prefix = "retention:${UUID.randomUUID()}"
            val keys = List(5) { "$prefix:$it" }
            keys.forEach { h.jdbc.update("INSERT INTO queue_resource_lock (resource_key, fence_counter) VALUES (?, 3)", it) }
            try {
                h.pass()
                // Other tests' leftovers are eligible too, so a pass removes at most batch of each kind.
                assertTrue(audits.count { h.auditExists(it) } >= 3)
                assertTrue(keys.count { h.lockExists(it) } >= 3)
            } finally {
                audits.forEach { h.jdbc.update("DELETE FROM queue_admin_audit WHERE command_id = ?", it) }
                keys.forEach { h.jdbc.update("DELETE FROM queue_resource_lock WHERE resource_key = ?", it) }
            }
        }
    }

    @Test
    fun auditUsesItsOwnCutoffIndependentlyOfJobDeletion() {
        withQueueReviewFixture { fixture ->
            val h = Harness(fixture, terminalDays = 1, auditDays = 30)
            val deletedJob = h.finish()
            val liveJob = h.enqueue()
            h.age(deletedJob, 2.0)
            val youngAuditOfDeletedJob = h.audit(deletedJob, 10.0)
            val oldAuditOfLiveJob = h.audit(liveJob, 40.0)
            try {
                val result = h.pass()
                assertNull(h.status(deletedJob))
                assertTrue(h.auditExists(youngAuditOfDeletedJob), "Audit must outlive its job until its own cutoff")
                assertFalse(h.auditExists(oldAuditOfLiveJob))
                assertEquals("QUEUED", h.status(liveJob))
                assertTrue(result.audit >= 1)
                assertTrue(h.counter("audit") >= 1.0)
            } finally {
                h.jdbc.update("DELETE FROM queue_admin_audit WHERE command_id = ?", youngAuditOfDeletedJob)
            }
        }
    }

    @Test
    fun resourceLockIsDeletedOnlyWhenUnownedAndUnreferenced() {
        withQueueReviewFixture { fixture ->
            val h = Harness(fixture)
            val prefix = "retention-${UUID.randomUUID()}"
            val free = "$prefix:free"
            val owned = "$prefix:owned"
            val referenced = "$prefix:referenced"
            val referencingJob = h.enqueue(resource = referenced)
            for (key in listOf(free, owned, referenced)) {
                h.jdbc.update("INSERT INTO queue_resource_lock (resource_key, fence_counter) VALUES (?, 4)", key)
            }
            h.jdbc.update(
                "UPDATE queue_resource_lock SET current_job_id = 999999999, current_attempt_no = 1, current_fence = 4, " +
                    "lease_expires_at_epoch_ms = ? WHERE resource_key = ?",
                h.clock.now() + 60_000, owned,
            )
            try {
                val result = h.pass()
                assertTrue(result.resourceLocks >= 1)
                assertFalse(h.lockExists(free))
                assertTrue(h.lockExists(owned), "An owned tombstone must stay")
                assertTrue(h.lockExists(referenced), "A tombstone with a pending job must stay")
                assertEquals(1, h.count("queue_job_resource", referencingJob))
                assertTrue(h.counter("resource_lock") >= 1.0)
            } finally {
                h.jdbc.update("DELETE FROM queue_resource_lock WHERE resource_key IN (?, ?, ?)", free, owned, referenced)
            }
        }
    }

    @Test
    fun aNewJobClaimsNormallyAfterItsResourceLockWasDeleted() {
        withQueueReviewFixture { fixture ->
            val h = Harness(fixture)
            val resource = "retention:${UUID.randomUUID()}"
            val first = h.finish(resource = resource)
            assertTrue(h.lockExists(resource))
            h.age(first, 2.0)

            h.pass()

            assertNull(h.status(first))
            assertFalse(h.lockExists(resource), "Unreferenced released lock must be deleted in the same pass")
            val second = h.enqueue(resource = resource)
            val token = h.claim(second)
            assertEquals(1L, token.resourceFences.getValue(resource))
            val context = TaskContext(second, token.attemptNo, token.fence, token.ownerInstance, token, h.store)
            assertTrue(h.store.complete(token, null, context.closeForHandlerReturn()))
            h.store.releaseAfterHandlerReturn(token)
            assertEquals("SUCCEEDED", h.status(second))
            assertTrue(h.lockExists(resource))
        }
    }

    @Test
    fun idempotencyKeyCreatesANewJobAfterTheOldOneWasDeleted() {
        withQueueReviewFixture { fixture ->
            val h = Harness(fixture)
            val key = "retention-key-${UUID.randomUUID()}"
            val first = h.finish(key = key)
            assertEquals(first, h.enqueue(key = key))
            h.age(first, 2.0)

            h.pass()

            assertNull(h.status(first))
            val second = h.enqueue(key = key)
            assertNotEquals(first, second)
            assertEquals("QUEUED", h.status(second))
        }
    }

    @Test
    fun aFileThatCannotBeDeletedKeepsTheJobRowsUntilALaterPass() {
        withQueueReviewFixture { fixture ->
            val h = Harness(fixture)
            val job = h.finish(artifact = true)
            h.age(job, 2.0)
            val file = h.artifactPath(job)
            val directory = file.parent
            val posix = Files.getFileStore(directory).supportsFileAttributeView("posix")
            assumeTrue(posix, "POSIX permissions are not supported on this filesystem; skipping")
            val original = Files.getPosixFilePermissions(directory)
            try {
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-xr-xr-x"))
                val probe = runCatching { Files.createFile(directory.resolve("probe")) }
                probe.onSuccess { Files.deleteIfExists(it) }
                assumeTrue(probe.isFailure, "Permissions do not restrict this user (root?); skipping")

                assertThrows(Exception::class.java) { h.pass() }

                assertEquals("SUCCEEDED", h.status(job))
                assertEquals(1, h.count("queue_artifact", job))
                assertTrue(Files.exists(file))
            } finally {
                Files.setPosixFilePermissions(directory, original)
            }
            h.pass()
            assertNull(h.status(job))
            assertFalse(Files.exists(file))
        }
    }

    @Test
    fun pathsEscapingTheArtifactRootOrCrossingSymlinksAreRejected() {
        withQueueReviewFixture { fixture ->
            val h = Harness(fixture)
            val outside = Files.createTempDirectory("queue-retention-outside-")
            try {
                val sentinel = Files.writeString(outside.resolve("keep.txt"), "keep")
                val traversal = h.finish()
                val viaLink = h.finish()
                for (job in listOf(traversal, viaLink)) h.age(job, 2.0)
                fun artifactRow(job: Long, path: String) = h.jdbc.update(
                    "INSERT INTO queue_artifact (artifact_id, job_id, execution_generation, attempt_no, fence, " +
                        "relative_path, storage_path, size_bytes, sha256) VALUES (?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), job, 1L, 1L, 1L, "keep.txt", path, 4L, "0".repeat(64),
                )
                artifactRow(traversal, "artifacts/$traversal/../../../${outside.fileName}/keep.txt")
                Files.createDirectories(h.root.resolve("artifacts/$viaLink"))
                Files.createSymbolicLink(h.root.resolve("artifacts/$viaLink/link"), outside)
                artifactRow(viaLink, "artifacts/$viaLink/link/keep.txt")

                assertThrows(Exception::class.java) { h.pass() }

                assertTrue(Files.exists(sentinel))
                assertEquals("SUCCEEDED", h.status(traversal))
                assertEquals("SUCCEEDED", h.status(viaLink))
            } finally {
                Files.deleteIfExists(outside.resolve("keep.txt"))
                Files.deleteIfExists(outside)
            }
        }
    }

    @Test
    fun retentionInputsAreValidated() {
        withQueueReviewFixture { fixture ->
            val h = Harness(fixture)
            fun build(terminal: Int = 30, audit: Int = 365, batch: Int = 500, interval: Long = 3_600_000) =
                QueueRetention(h.manager, fixture.transactionManager, h.clock, h.store, h.meters, terminal, audit, batch, interval)
            build()
            assertThrows(IllegalArgumentException::class.java) { build(terminal = -1) }
            assertThrows(IllegalArgumentException::class.java) { build(audit = -1) }
            assertThrows(IllegalArgumentException::class.java) { build(batch = 0) }
            assertThrows(IllegalArgumentException::class.java) { build(batch = 5001) }
            assertThrows(IllegalArgumentException::class.java) { build(interval = 999) }
        }
    }

    @Test
    fun runtimeSchedulesRetentionPassesOnItsOwnInterval() {
        withQueueReviewFixture { fixture ->
            val h = Harness(fixture)
            val retention = QueueRetention(h.manager, fixture.transactionManager, h.clock, h.store, h.meters, 1, 1, 500, 1000)
            val job = h.finish()
            h.age(job, 2.0)
            QueueWorkerRuntime(h.store, fixture.registry, h.clock, h.meters, workers = 1, pollMillis = 20,
                dataDirectory = fixture.dataDirectory.toString(), dbConnectionBudget = 4, retention = retention).use { runtime ->
                runtime.start()
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
                while (h.status(job) != null && System.nanoTime() < deadline) Thread.sleep(100)
                assertNull(h.status(job), "The cleanup scheduler never ran a retention pass")
            }
        }
    }
}
