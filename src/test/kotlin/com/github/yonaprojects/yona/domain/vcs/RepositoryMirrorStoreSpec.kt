package com.github.yonaprojects.yona.domain.vcs

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import jakarta.persistence.EntityManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RepositoryMirrorStoreSpec @Autowired constructor(
    private val store: RepositoryMirrorStore,
    private val mirrors: RepositoryMirrorRepository,
    private val projects: ProjectRepository,
    private val jdbc: JdbcTemplate,
    private val entityManager: EntityManager,
    transactionManager: PlatformTransactionManager
) : AbstractIntegrationTest() {
    private val transaction = TransactionTemplate(transactionManager)

    init {
        val created = mutableListOf<Long>()
        fun mirror(): RepositoryMirror {
            val project = projects.save(Project(owner = "mirror-state", name = UUID.randomUUID().toString(), vcs = "SUBVERSION"))
            created += project.id!!
            return mirrors.saveAndFlush(RepositoryMirror(project = project, sourceUrl = "https://svn.example.test/root"))
        }
        afterSpec {
            created.forEach { id -> mirrors.findByProjectId(id)?.let(mirrors::delete); projects.deleteById(id) }
        }
        describe("durable mirror checkpoints and DB-time fencing") {
            it("atomically claims once across competing dispatchers and preserves fixed R after restart") {
                val id = mirror().id!!
                val gate = CountDownLatch(1)
                val executor = Executors.newFixedThreadPool(2)
                val results = try {
                    val futures = (1..2).map { n -> executor.submit(Callable { gate.await(); n to store.claim(id, "worker-$n", 60) }) }
                    gate.countDown()
                    futures.map { it.get(10, TimeUnit.SECONDS) }
                } finally { executor.shutdownNow() }
                results.count { it.second != null } shouldBe 1
                val winner = results.single { it.second != null }
                val owner = "worker-${winner.first}"
                val fence = winner.second!!
                store.observe(id, owner, fence, "source-uuid", 3, "local-uuid", 0)
                store.verified(id, owner, fence, 0, 0)
                store.fenced(id, owner, fence) { it.lastIndexedRevision = 0 }
                store.release(id, owner, fence)
                (id in store.due(100)) shouldBe true
                val next = store.claim(id, "restarted", 60)!!
                next shouldBe fence + 1
                store.observe(id, "restarted", next, "source-uuid", 7, "local-uuid", 0)
                store.snapshot(id).initialImportTargetRevision shouldBe 3L
                shouldThrow<MirrorLeaseLost> { store.fenced(id, owner, fence) { it.lastIndexedRevision = 99 } }
                store.snapshot(id).lastIndexedRevision shouldBe 0L
            }
            it("rolls back checkpoint mutations and refuses UUID and cursor corruption") {
                val id = mirror().id!!
                val fence = store.claim(id, "a", 60)!!
                store.observe(id, "a", fence, "source", 3, "local", 0)
                shouldThrow<IllegalStateException> { store.fenced(id, "a", fence) { it.lastError = "rollback"; error("rollback") } }
                store.snapshot(id).lastError shouldBe null
                shouldThrow<MirrorFailure> { store.observe(id, "a", fence, "changed", 3, "local", 0) }
                shouldThrow<MirrorFailure> { store.fenced(id, "a", fence) { it.lastIndexedRevision = 3 } }
                store.snapshot(id).lastIndexedRevision shouldBe -1L
                shouldThrow<MirrorFailure> { store.observe(id, "a", fence, "source", 2, "local", 0) }
                shouldThrow<MirrorFailure> { store.fenced(id, "a", fence) { it.initialImportTargetRevision = 4 } }
            }
            it("pause cancels publication but retry never steals a live lease or resets identity") {
                val id = mirror().id!!
                val fence = store.claim(id, "a", 60)!!
                store.observe(id, "a", fence, "source", 8, "local", 0)
                store.pause(id)
                store.heartbeat(id, "a", fence, 60) shouldBe false
                shouldThrow<MirrorLeaseLost> { store.verified(id, "a", fence, 0, 0) }
                shouldThrow<MirrorFailure> { store.retry(id) }.code shouldBe "MIRROR_BUSY"
                store.release(id, "a", fence)
                store.retry(id)
                val current = store.snapshot(id)
                current.enabled shouldBe true
                current.generation shouldBe 1L
                current.initialImportTargetRevision shouldBe 8L
                current.lastVerifiedRevision shouldBe -1L
            }
            it("expired execution cannot publish and another execution receives a higher fence") {
                val id = mirror().id!!
                val first = store.claim(id, "old", 60)!!
                val expired = store.databaseNow().minusSeconds(2)
                // Use the same Instant/UTC mapping as production, not JDBC's JVM-zone Timestamp binding.
                transaction.executeWithoutResult {
                    entityManager.createQuery("update RepositoryMirror m set m.leaseUntil = :expired where m.id = :id")
                        .setParameter("expired", expired).setParameter("id", id).executeUpdate()
                }
                val storedExpiry = requireNotNull(store.snapshot(id).leaseUntil)
                storedExpiry shouldBe expired
                storedExpiry.isBefore(store.databaseNow()) shouldBe true
                val second = store.claim(id, "new", 60)!!
                second shouldBe first + 1
                store.heartbeat(id, "old", first, 60) shouldBe false
                shouldThrow<MirrorLeaseLost> { store.complete(id, "old", first, 60) }
                store.release(id, "old", first)
                store.snapshot(id).leaseOwner shouldBe "new"
            }
            it("quarantines malformed checkpoints and stops after five transient failures") {
                val broken = mirror().id!!
                val brokenFence = store.claim(broken, "broken", 60)!!
                jdbc.update("update repository_mirror set last_indexed_revision = 9 where id = ?", broken)
                shouldThrow<MirrorFailure> { store.fenced(broken, "broken", brokenFence) {} }
                store.fail(broken, "broken", brokenFence, MirrorFailure("INVALID_CHECKPOINT", attention = true))
                store.snapshot(broken).status shouldBe RepositoryMirrorStatus.NEEDS_ATTENTION
                val id = mirror().id!!
                repeat(5) { attempt ->
                    val fence = store.claim(id, "retry", 60)!!
                    store.fail(id, "retry", fence, MirrorFailure("SOURCE_UNAVAILABLE", transient = true))
                    store.release(id, "retry", fence)
                    val current = store.snapshot(id)
                    current.retryCount shouldBe attempt + 1
                    if (attempt < 4) {
                        current.status shouldBe RepositoryMirrorStatus.INITIAL_IMPORT
                        current.nextSyncAt!!.isAfter(store.databaseNow()) shouldBe true
                        jdbc.update("update repository_mirror set next_sync_at = null where id = ?", id)
                    }
                }
                store.snapshot(id).status shouldBe RepositoryMirrorStatus.FAILED
                store.claim(id, "retry", 60) shouldBe null
            }
            it("enforces one mirror per project with Unicode and Long checkpoints") {
                val first = mirror()
                shouldThrow<org.springframework.dao.DataIntegrityViolationException> {
                    mirrors.saveAndFlush(RepositoryMirror(project = first.project, sourceUrl = "https://svn.example.test/다른"))
                }
                val id = first.id!!
                val fence = store.claim(id, "unicode", 60)!!
                store.observe(id, "unicode", fence, "원본", Int.MAX_VALUE.toLong() + 1, "대상", 0)
                store.snapshot(id).initialImportTargetRevision shouldBe Int.MAX_VALUE.toLong() + 1
            }
        }
    }
}
