package com.github.yonaprojects.yona.domain.issue

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectScope
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files

@TestPropertySource(properties = ["yona.search.backend=lucene"])
class IssueSearchQueueSpec @Autowired constructor(
    private val em: EntityManager, private val jobs: IssueSearchJobs,
    private val changes: IssueSearchChanges, private val tasks: IssueSearchTasks,
    private val index: IssueSearchIndex, transactions: PlatformTransactionManager
) : AbstractIntegrationTest() {
    companion object {
        private val path = Files.createTempDirectory("yona-search-queue-")
        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("yona.search.index-dir") { path.toString() }
            registry.add("yona.search.initial-delay-millis") { "3600000" } // Drive the scheduler explicitly.
            registry.add("yona.search.batch-window-millis") { "100" }
        }
    }
    init {
        val transaction = TransactionTemplate(transactions)
        fun jobCount(): Long = transaction.execute {
            em.createQuery("select count(j) from QueueJob j where j.taskType = :type", Long::class.javaObjectType)
                .setParameter("type", IssueSearchTasks.TYPE).singleResult
        }!!
        suspend fun drain() {
            withTimeout(30_000) {
                do {
                    jobs.schedule()
                    delay(50)
                } while (changes.window().first != null || !index.status.ready)
                // Wait for the queue receipt to finish as well as the index/dirty-ID acknowledgement.
                while (transaction.execute {
                    em.createQuery("select count(j) from QueueJob j where j.taskType = :type and j.finishedAt is null", Long::class.javaObjectType)
                        .setParameter("type", IssueSearchTasks.TYPE).singleResult
                }!! > 0) delay(50)
            }
        }

        it("전역 고정 창으로 여러 이슈를 한 작업에 묶고 유휴 시 작업을 만들지 않는다") {
            val ids = transaction.execute {
                val public = Project(name = "queue-search", owner = "poc", projectScope = ProjectScope.PUBLIC)
                val private = Project(name = "private-index", owner = "poc", projectScope = ProjectScope.PRIVATE)
                em.persist(public); em.persist(private)
                listOf(public, private).map { project ->
                    val issue = Issue(title = "indexcoverage 초기", project = project, number = 1)
                    em.persist(issue)
                    val comment = IssueComment(issue = issue, contents = "동기화 검증")
                    em.persist(comment)
                    issue.id!! to comment.id!!
                }
            }!!
            drain() // One initial/recovery full scan.
            index.search("indexcoverage").map { it.id }.containsAll(ids.map { it.first }) shouldBe true
            val initialJobs = jobCount()
            val firstSuccess = index.status.lastSuccess
            repeat(3) { jobs.schedule() }
            jobCount() shouldBe initialJobs
            index.status.lastSuccess shouldBe firstSuccess

            transaction.executeWithoutResult {
                ids.forEach { (_, commentId) -> em.find(IssueComment::class.java, commentId).contents = "새로운 배치 내용" }
            }
            changes.snapshot().issueIds.toSet() shouldBe ids.map { it.first }.toSet()
            jobs.schedule() // Within the global fixed window.
            jobCount() shouldBe initialJobs
            drain()
            jobCount() shouldBe initialJobs + 1
            index.status.scanned shouldBe 2 // Only dirty issues, not the whole DB.
            index.search("새로운 배치").map { it.id }.containsAll(ids.map { it.first }) shouldBe true

            val beforeRollback = changes.window()
            transaction.executeWithoutResult { status ->
                em.find(IssueComment::class.java, ids.first().second).contents = "롤백"
                em.flush()
                status.setRollbackOnly()
            }
            changes.window() shouldBe beforeRollback
            changes.snapshot().eventIds shouldBe emptyList()

            transaction.executeWithoutResult {
                ids.forEach { (issueId, commentId) ->
                    em.remove(em.find(IssueComment::class.java, commentId))
                    em.remove(em.find(Issue::class.java, issueId))
                }
            }
            drain()
            index.search("새로운 배치").map { it.id }.intersect(ids.map { it.first }.toSet()) shouldBe emptySet()
            val finished = jobCount()
            repeat(3) { jobs.schedule() }
            jobCount() shouldBe finished
        }

        it("독립 트랜잭션은 직렬화하지 않고 늦게 커밋한 변경도 승인에서 제외한다") {
            val ids = transaction.execute {
                val project = Project(name = "queue-concurrent", owner = "poc", projectScope = ProjectScope.PUBLIC)
                em.persist(project)
                (1L..2L).map { number ->
                    Issue(title = "concurrentneedle", project = project, number = number).also(em::persist).id!!
                }
            }!!
            changes.acknowledge(changes.snapshot())
            val flushed = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val lateCommit = executor.submit {
                    transaction.executeWithoutResult {
                        em.find(Issue::class.java, ids[0]).title = "latecommitneedle"
                        em.flush()
                        flushed.countDown()
                        check(release.await(20, java.util.concurrent.TimeUnit.SECONDS))
                    }
                }
                check(flushed.await(10, java.util.concurrent.TimeUnit.SECONDS))
                val earlyCommit = executor.submit {
                    transaction.executeWithoutResult {
                        em.find(Issue::class.java, ids[1]).title = "earlycommitneedle"
                        em.flush()
                    }
                }
                // Must finish while the first transaction still holds its source-row locks.
                earlyCommit.get(10, java.util.concurrent.TimeUnit.SECONDS)
                val visible = changes.snapshot()
                visible.issueIds shouldBe listOf(ids[1])
                changes.acknowledge(visible)
                release.countDown()
                lateCommit.get(10, java.util.concurrent.TimeUnit.SECONDS)
                changes.snapshot().issueIds shouldBe listOf(ids[0])
                drain()
                index.search("latecommitneedle").map { it.id } shouldBe listOf(ids[0])
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }

        it("처리 중 들어온 변경은 별도 이벤트로 남기고 새 워커에서도 처리한다") {
            val id = transaction.execute {
                val project = Project(name = "queue-race", owner = "poc", projectScope = ProjectScope.PUBLIC)
                em.persist(project)
                val issue = Issue(title = "첫번째 변경", project = project, number = 1)
                em.persist(issue)
                issue.id!!
            }!!
            val oldBatch = changes.snapshot()
            index.update(oldBatch.issueIds, tasks::load)
            transaction.executeWithoutResult { em.find(Issue::class.java, id).title = "실행중 새변경" }
            changes.acknowledge(oldBatch)
            changes.snapshot().issueIds.contains(id) shouldBe true
            // A newly created recorder has no in-memory timer or ID set to restore.
            val restarted = IssueSearchChanges(em, em.entityManagerFactory, transactions)
            restarted.snapshot() shouldBe changes.snapshot()
            drain()
            index.search("실행중 새변경").map { it.id }.contains(id) shouldBe true
            changes.snapshot().eventIds shouldBe emptyList()
        }
    }
}
