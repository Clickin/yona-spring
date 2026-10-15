package com.github.yonaprojects.yona.domain.vcs

import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.queue.QueueStatus
import com.github.yonaprojects.yona.queue.QueueWorkerRuntime
import com.github.yonaprojects.yona.queue.QueueWorkerStore
import com.github.yonaprojects.yona.queue.RetryableTaskFailure
import com.github.yonaprojects.yona.queue.acceptance.JdbcQueueAcceptanceFixture
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.TreeFormatter
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class GitMaintenanceTest {
    @Test
    fun ordinaryGcPacksReachableObjectsAndRetainsOldLooseAndPackedObjectsDuringRefPublication() {
        val root = Files.createTempDirectory("yona-gc-safety-")
        try {
            val directory = root.resolve("owner/repo.git")
            Git.init().setDirectory(directory.toFile()).setBare(true).setInitialBranch("main").call().close()
            val tasks = GitMaintenanceTasks(mockk(), root.toFile())
            FileRepositoryBuilder().setGitDir(directory.toFile()).build().use { repo ->
                val oldCommit = commit(repo, "previously reachable")
                update(repo, "refs/heads/main", oldCommit)
                tasks.collect(directory, NullProgressMonitor.INSTANCE)
                assertTrue(Files.list(directory.resolve("objects/pack")).use { files -> files.anyMatch { it.toString().endsWith(".pack") } })
                Files.list(directory.resolve("objects/pack")).use { files ->
                    files.forEach { Files.setLastModifiedTime(it, FileTime.from(Instant.now().minusSeconds(7200))) }
                }
                repo.updateRef("refs/heads/main").apply { isForceUpdate = true }.delete()
                val newCommit = commit(repo, "new main")
                update(repo, "refs/heads/main", newCommit)
                val loose = repo.newObjectInserter().use { inserter ->
                    inserter.insert(Constants.OBJ_BLOB, "unpublished web write".toByteArray()).also { inserter.flush() }
                }
                val loosePath = directory.resolve("objects/${loose.name.take(2)}/${loose.name.drop(2)}")
                Files.setLastModifiedTime(loosePath, FileTime.from(Instant.parse("1960-01-01T00:00:00Z")))
                repo.config.setString("gc", null, "pruneExpire", "now")
                repo.config.setString("gc", null, "prunePackExpire", "now")
                repo.config.setBoolean("pack", null, "prunePreserved", true)
                repo.config.save()
                val before = Files.readString(directory.resolve("config"))
                var published = false
                val monitor = object : ProgressMonitor by NullProgressMonitor.INSTANCE {
                    override fun beginTask(title: String, totalWork: Int) {
                        // Packing starts after GC's ref snapshot. A receive/web writer can publish then.
                        if (!published) { published = true; update(repo, "refs/heads/concurrent", oldCommit) }
                    }
                }
                tasks.withRepositoryLock(directory) { tasks.collect(directory, monitor) }
                assertTrue(published)
                FileRepositoryBuilder().setGitDir(directory.toFile()).build().use { fresh ->
                    assertEquals(oldCommit, fresh.resolve("refs/heads/concurrent"))
                    assertTrue(fresh.objectDatabase.has(oldCommit), "Old packed objects must survive a ref published during GC")
                    assertTrue(fresh.objectDatabase.has(newCommit))
                    assertTrue(fresh.objectDatabase.has(loose), "Unreferenced objects are never pruned, regardless of age or repo config")
                }
                assertEquals(before, Files.readString(directory.resolve("config")))
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun repositoryGuardExcludesAnotherJvmAndRefOrObjectWritesResetIdleTime() {
        val root = Files.createTempDirectory("yona-gc-lock-")
        try {
            val directory = root.resolve("owner/repo.git")
            Git.init().setDirectory(directory.toFile()).setBare(true).call().close()
            val tasks = GitMaintenanceTasks(mockk(), root.toFile())
            val source = root.resolve("HeldGitMaintenanceLock.java")
            Files.writeString(source, """
                import java.nio.channels.FileChannel;
                import java.nio.file.Path;
                import static java.nio.file.StandardOpenOption.*;
                class HeldGitMaintenanceLock {
                    public static void main(String[] args) throws Exception {
                        try (var channel = FileChannel.open(Path.of(args[0]), CREATE, WRITE); var lock = channel.lock()) {
                            System.out.println("LOCKED"); System.out.flush(); System.in.read();
                        }
                    }
                }
            """.trimIndent())
            val child = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                source.toString(), directory.resolve("yona-gc.lock").toString()).redirectErrorStream(true).start()
            try {
                assertEquals("LOCKED", CompletableFuture.supplyAsync { child.inputStream.bufferedReader().readLine() }.get(30, TimeUnit.SECONDS))
                assertEquals("GIT_GC_BUSY", assertThrows(RetryableTaskFailure::class.java) {
                    tasks.withRepositoryLock(directory) { error("Concurrent GC entered") }
                }.code)
                child.outputStream.write('\n'.code); child.outputStream.flush()
                assertTrue(child.waitFor(30, TimeUnit.SECONDS))
                assertEquals(0, child.exitValue())
            } finally { if (child.isAlive) { child.destroyForcibly(); child.waitFor(30, TimeUnit.SECONDS) } }
            tasks.withRepositoryLock(directory) { assertTrue(Files.exists(directory.resolve("yona-gc.lock"))) }
            age(directory)
            val cutoff = Instant.now().minusSeconds(3600)
            assertTrue(tasks.idle(directory, cutoff))
            val ref = directory.resolve("refs/heads/new-web-commit")
            Files.writeString(ref, "1".repeat(40))
            assertFalse(tasks.idle(directory, cutoff))
            age(directory)
            Files.createDirectories(directory.resolve("objects/ab"))
            assertFalse(tasks.idle(directory, cutoff))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun queuedManualGcHasProjectScopedStatusAndAutomaticGcRechecksActivity() {
        Harness().use { h ->
            val project = h.project(81001)
            val other = h.project(81002).apply { vcs = null }
            h.seed(project); h.seed(other)
            val automatic = h.fixture.queue.enqueue(GitMaintenanceTasks.TYPE, 1,
                h.mapper.writeValueAsBytes(mapOf("projectId" to project.id, "automatic" to true)), Instant.EPOCH, null, "git-maintenance:auto").jobId
            val manual = h.service.enqueue(other, "user:123")
            assertEquals(automatic, h.service.status(project)?.id)
            assertEquals(manual, h.service.status(other)?.id)
            assertEquals(QueueStatus.QUEUED, h.service.status(other)?.status)
            assertNull(h.service.status(h.project(81003)))
            h.runtime().use { worker ->
                worker.start()
                h.awaitStatus(automatic, "SUCCEEDED")
                h.awaitStatus(manual, "SUCCEEDED")
            }
            assertEquals("git.gc.skipped-active", h.fixture.jdbc.queryForObject("SELECT progress_stage FROM queue_job WHERE id = ?", String::class.java, automatic))
            assertEquals("git.gc.complete", h.fixture.jdbc.queryForObject("SELECT progress_stage FROM queue_job WHERE id = ?", String::class.java, manual))
            assertThrows(IllegalArgumentException::class.java) { h.tasks.gitMaintenanceTask().validate(h.mapper.readTree("""{"projectId":1,"automatic":false,"path":"../other"}""")) }
            val missing = h.service.enqueue(other, "user:123")
            h.tasks.directory(other).toFile().deleteRecursively()
            h.runtime().use { worker -> worker.start(); h.awaitStatus(missing, "FAILED") }
            assertEquals("GIT_REPOSITORY_UNAVAILABLE", h.fixture.jdbc.queryForObject("SELECT error_code FROM queue_job WHERE id = ?", String::class.java, missing))
        }
    }

    @Test
    fun automaticSqlScanIncludesLegacyGitProjectsAndRespectsCadence() {
        Harness().use { h ->
            val project = Project(owner = "owner", name = "scan-${UUID.randomUUID()}", vcs = null,
                createdDate = Instant.now().minusSeconds(7200))
            val transaction = TransactionTemplate(h.fixture.transactionManager)
            transaction.executeWithoutResult { h.manager.persist(project); h.manager.flush() }
            try {
                h.seed(project)
                val pages = (h.fixture.jdbc.queryForObject("SELECT COUNT(*) FROM project", Long::class.java)!! / 64 + 2).toInt()
                repeat(pages) { h.service.scanIdle() }
                val job = h.service.status(project)!!.id
                repeat(pages) { h.service.scanIdle() }
                assertEquals(job, h.service.status(project)?.id, "A recent/pending collector must not be admitted again")
                assertFalse(Files.exists(h.tasks.directory(project).resolve("yona-gc.lock")))
                age(h.tasks.directory(project))
                h.runtime().use { worker -> worker.start(); h.awaitStatus(job, "SUCCEEDED") }
                assertEquals("git.gc.complete", h.fixture.jdbc.queryForObject("SELECT progress_stage FROM queue_job WHERE id = ?", String::class.java, job))
            } finally {
                transaction.executeWithoutResult { h.manager.find(Project::class.java, project.id)?.let(h.manager::remove) }
            }
        }
    }

    private class Harness : AutoCloseable {
        val fixture = JdbcQueueAcceptanceFixture()
        val mapper = JsonMapper.builder().build()
        val manager = SharedEntityManagerCreator.createSharedEntityManager((fixture.transactionManager as JpaTransactionManager).entityManagerFactory!!)
        val projects = mockk<ProjectRepository>()
        private val known = mutableMapOf<Long, Project>()
        private val root = fixture.dataDirectory.resolve("git")
        val tasks = GitMaintenanceTasks(projects, root.toFile())
        val service = GitMaintenanceService(fixture.queue, manager, projects, tasks, mapper, fixture.transactionManager)
        init {
            every { projects.findById(any()) } answers { Optional.ofNullable(known[firstArg<Long>()]) }
            fixture.registry.register(tasks.gitMaintenanceTask())
        }
        fun project(id: Long) = Project(id = id, owner = "owner", name = "repo-$id", vcs = "GIT", createdDate = Instant.now().minusSeconds(7200))
        fun seed(project: Project) {
            known[project.id!!] = project
            Git.init().setDirectory(root.resolve("${project.owner}/${project.name}.git").toFile()).setBare(true).call().close()
        }
        fun runtime(): QueueWorkerRuntime {
            val meters = SimpleMeterRegistry()
            val clock = fixture.contextQueueClock()
            val store = QueueWorkerStore(manager, fixture.transactionManager, clock, fixture.registry, fixture.dataSource,
                fixture.dataDirectory.toString(), meterRegistry = meters)
            return QueueWorkerRuntime(store, fixture.registry, clock, meters, workers = 1, pollMillis = 20,
                shutdownGraceMillis = 1000, dataDirectory = fixture.dataDirectory.toString(), dbConnectionBudget = 4)
        }
        fun awaitStatus(job: Long, status: String) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (fixture.jobRow(job)?.status != status && System.nanoTime() < deadline) Thread.sleep(20)
            assertEquals(status, fixture.jobRow(job)?.status)
        }
        override fun close() {
            val jobs = "SELECT id FROM queue_job WHERE task_type = 'git.gc'"
            val failures = fixture.jdbc.queryForObject("SELECT COUNT(*) FROM queue_job WHERE id IN ($jobs) AND status = 'FAILED'", Long::class.java)!!
            fixture.jdbc.update("UPDATE queue_resource_lock SET current_job_id = NULL, current_attempt_no = NULL, current_fence = NULL, lease_expires_at_epoch_ms = NULL WHERE current_job_id IN ($jobs)")
            for (table in listOf("queue_artifact", "queue_admin_audit", "queue_idempotency_key", "queue_job_resource", "queue_attempt")) fixture.jdbc.update("DELETE FROM $table WHERE job_id IN ($jobs)")
            fixture.jdbc.update("DELETE FROM queue_job WHERE id IN ($jobs)")
            fixture.jdbc.update("UPDATE queue_meta SET counter_value = counter_value - ? WHERE counter_name = 'failed-jobs'", failures)
            fixture.close()
        }
    }

    private fun commit(repo: Repository, message: String): ObjectId = repo.newObjectInserter().use { inserter ->
        val tree = inserter.insert(TreeFormatter())
        inserter.insert(CommitBuilder().apply {
            setTreeId(tree); author = PersonIdent("tester", "tester@example.invalid"); committer = author; setMessage(message)
        }).also { inserter.flush() }
    }
    private fun update(repo: Repository, ref: String, id: ObjectId) {
        repo.updateRef(ref).apply { isForceUpdate = true; setNewObjectId(id) }.update()
    }
    private fun age(directory: Path) {
        val old = FileTime.from(Instant.now().minusSeconds(7200))
        Files.walk(directory).use { paths -> paths.forEach { Files.setLastModifiedTime(it, old) } }
    }
}
