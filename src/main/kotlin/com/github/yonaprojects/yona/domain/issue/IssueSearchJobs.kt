package com.github.yonaprojects.yona.domain.issue

import com.github.yonaprojects.yona.queue.Queue
import com.github.yonaprojects.yona.queue.QueueStatus
import com.github.yonaprojects.yona.queue.TaskDefinition
import jakarta.persistence.EntityManager
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["yona.search.backend"], havingValue = "lucene")
class IssueSearchTasks(private val index: IssueSearchIndex, private val em: EntityManager,
    private val changes: IssueSearchChanges,
    transactions: PlatformTransactionManager) {
    private val read = TransactionTemplate(transactions).apply { isReadOnly = true }

    fun batch(after: Long): List<IssueSearchDocument> = read.execute {
        val issues = em.createQuery("select i from Issue i where i.id > :after order by i.id", Issue::class.java)
            .setParameter("after", after).setMaxResults(200).resultList
        if (issues.isEmpty()) return@execute emptyList()
        val comments = em.createQuery("select c from IssueComment c where c.issue.id in :ids order by c.id", IssueComment::class.java)
            .setParameter("ids", issues.map { it.id!! }).resultList.groupBy { it.issue.id }
        issues.map { IssueSearchDocument(it.id!!, it.title, it.body.orEmpty(),
            comments[it.id].orEmpty().map { comment -> comment.id!! to comment.contents }) }
    }!!

    fun load(ids: List<Long>): List<IssueSearchDocument> = read.execute {
        val issues = em.createQuery("select i from Issue i where i.id in :ids order by i.id", Issue::class.java)
            .setParameter("ids", ids).resultList
        val comments = em.createQuery("select c from IssueComment c where c.issue.id in :ids order by c.id", IssueComment::class.java)
            .setParameter("ids", ids).resultList.groupBy { it.issue.id }
        issues.map { IssueSearchDocument(it.id!!, it.title, it.body.orEmpty(),
            comments[it.id].orEmpty().map { comment -> comment.id!! to comment.contents }) }
    }!!

    @Bean
    fun issueSearchSyncTask() = TaskDefinition(
        type = TYPE, payloadVersion = 1, validate = { require(it.isObject && it.size() == 0) },
        resourceKeys = { listOf("search:issues") }, replaySafe = true, laneLimit = 1,
        handler = { context, _ ->
            val batch = changes.snapshot()
            val rebuild = batch.rebuild || !index.status.ready
            val progress: (Long) -> Unit = { count ->
                context.checkpoint()
                context.progress(if (rebuild) "search.rebuild" else "search.update", mapOf("scanned" to count))
            }
            if (rebuild) index.synchronize(::batch, progress)
            else if (batch.issueIds.isNotEmpty()) index.update(batch.issueIds, ::load, progress)
            context.checkpoint()
            changes.acknowledge(batch)
        }
    )

    companion object { const val TYPE = "search.issues.sync" }
}

/** One fixed window from the oldest committed pending event; idle polls never scan issue content. */
@Service
@ConditionalOnProperty(name = ["yona.search.backend"], havingValue = "lucene")
class IssueSearchJobs(private val queue: Queue, private val em: EntityManager,
    private val changes: IssueSearchChanges,
    @org.springframework.beans.factory.annotation.Value("\${yona.search.batch-window-millis:2000}") private val windowMillis: Long = 2000,
    transactions: PlatformTransactionManager) {
    private val admission = TransactionTemplate(transactions)
    private val logger = org.slf4j.LoggerFactory.getLogger(javaClass)

    private var initialized = false
    init { require(windowMillis > 0) }

    @Scheduled(fixedDelayString = "\${yona.search.poll-millis:500}", initialDelayString = "\${yona.search.initial-delay-millis:1000}")
    fun schedule() {
        try {
            if (!initialized) {
                changes.requestRebuild() // One recovery scan per process start, never an idle periodic scan.
                initialized = true
                submit()
            } else if (changes.window().due(System.currentTimeMillis(), windowMillis)) submit()
        } catch (failure: Exception) {
            // Search admission failures never run in (or roll back) an issue-writing transaction.
            logger.warn("Unable to enqueue issue index synchronization; next poll will retry", failure)
        }
    }

    fun enqueue(): Long {
        changes.requestRebuild()
        return submit()
    }

    @Synchronized
    private fun submit(): Long = admission.execute {
        val active = em.createQuery("select j.id from QueueJob j where j.taskType = :type and j.status in :statuses", Long::class.javaObjectType)
            .setParameter("type", IssueSearchTasks.TYPE)
            .setParameter("statuses", listOf(QueueStatus.QUEUED, QueueStatus.RUNNING, QueueStatus.RETRY_WAIT,
                QueueStatus.CANCEL_REQUESTED, QueueStatus.RECOVERY_REQUIRED, QueueStatus.BLOCKED_UNSUPPORTED))
            .setMaxResults(1).resultList.firstOrNull()
        active ?: queue.enqueue(IssueSearchTasks.TYPE, 1, "{}".toByteArray(), Instant.EPOCH, null, "search:issues").jobId
    }!!
}
