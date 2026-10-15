package com.github.yonaprojects.yona.domain.vcs

import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.queue.Queue
import com.github.yonaprojects.yona.queue.QueueAdmissionException
import com.github.yonaprojects.yona.queue.QueueJobSnapshot
import com.github.yonaprojects.yona.queue.QueueStatus
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Instant

/** Project-manager authorization belongs to the caller; this module never accepts a repository path. */
@Service
class GitMaintenanceService(
    private val queue: Queue,
    private val entityManager: EntityManager,
    private val projects: ProjectRepository,
    private val tasks: GitMaintenanceTasks,
    private val mapper: ObjectMapper,
    transactionManager: PlatformTransactionManager,
    @Value("\${yona.git.maintenance.enabled:true}") private val enabled: Boolean = true,
    @Value("\${yona.git.maintenance.idle-millis:3600000}") private val idleMillis: Long = 3_600_000,
    @Value("\${yona.git.maintenance.cadence-millis:86400000}") private val cadenceMillis: Long = 86_400_000,
    @Value("\${yona.git.maintenance.scan-batch:64}") private val scanBatch: Int = 64,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val admission = TransactionTemplate(transactionManager)
    private val reads = TransactionTemplate(transactionManager).apply { isReadOnly = true }
    private var cursor = 0L

    init {
        require(idleMillis > 0 && cadenceMillis > 0 && scanBatch in 1..1000)
    }

    @Transactional
    fun enqueue(project: Project, callerScope: String): Long {
        require(project.id != null && (project.vcs ?: "GIT").equals("GIT", ignoreCase = true)) { "Git project required" }
        tasks.directory(project)
        return submit(project.id!!, false, null, callerScope)
    }

    /** Latest snapshot is selected by the stable project resource, never by a caller-supplied job ID. */
    @Transactional(readOnly = true)
    fun status(project: Project): QueueJobSnapshot? {
        val id = project.id ?: return null
        return entityManager.createQuery(
            "select new com.github.yonaprojects.yona.queue.QueueJobSnapshot(" +
                "j.id,j.taskType,j.payloadVersion,j.status,j.createdAt,j.scheduledAt," +
                "j.executionGeneration,j.generationAttemptNo,j.attemptCount,j.rowVersion) " +
                "from QueueJobResource r join r.job j where r.id.resourceKey = :resource " +
                "and j.taskType = :type order by j.id desc", QueueJobSnapshot::class.java,
        ).setParameter("resource", GitMaintenanceTasks.resource(id))
            .setParameter("type", GitMaintenanceTasks.TYPE).setMaxResults(1).resultList.firstOrNull()
    }

    /** One bounded keyset page per tick. Filesystem inspection and GC run only in the queue worker. */
    @Scheduled(
        initialDelayString = "\${yona.git.maintenance.scan-millis:60000}",
        fixedDelayString = "\${yona.git.maintenance.scan-millis:60000}",
    )
    fun scanIdle() {
        if (!enabled) return
        val now = Instant.now()
        val candidates = reads.execute {
            entityManager.createQuery(
                "select p.id from Project p where p.id > :cursor and upper(coalesce(p.vcs,'GIT')) = 'GIT' " +
                    "and coalesce(p.lastPushedDate,p.createdDate) <= :idleBefore " +
                    "and not exists (select r.id.jobId from QueueJobResource r join r.job j " +
                    "where r.id.resourceKey = concat('git:',cast(p.id as string)) and j.taskType = :type " +
                    "and (j.createdAt > :recent or j.status in :pending)) order by p.id", Long::class.javaObjectType,
            ).setParameter("cursor", cursor).setParameter("idleBefore", now.minusMillis(idleMillis))
                .setParameter("type", GitMaintenanceTasks.TYPE)
                .setParameter("recent", now.minusMillis(cadenceMillis).toEpochMilli())
                .setParameter("pending", listOf(QueueStatus.QUEUED, QueueStatus.RUNNING, QueueStatus.RETRY_WAIT,
                    QueueStatus.CANCEL_REQUESTED, QueueStatus.RECOVERY_REQUIRED, QueueStatus.BLOCKED_UNSUPPORTED))
                .setMaxResults(scanBatch).resultList
        } ?: emptyList()
        if (candidates.isEmpty()) {
            cursor = 0
            return
        }
        for (id in candidates) {
            try {
                admission.executeWithoutResult {
                    val project = projects.findById(id).orElse(null)
                    if (project != null && (project.vcs ?: "GIT").equals("GIT", ignoreCase = true)) {
                        val lastWrite = project.lastPushedDate ?: project.createdDate
                        if (lastWrite != null && !lastWrite.isAfter(now.minusMillis(idleMillis))) {
                            submit(id, true, "$id:${now.toEpochMilli() / cadenceMillis}", "git-maintenance:auto")
                        }
                    }
                }
                cursor = id
            } catch (failure: QueueAdmissionException) {
                // Leave this candidate for the next tick when admission is available again.
                logger.warn("Automatic Git maintenance admission paused: {}", failure.code)
                return
            } catch (failure: Exception) {
                cursor = id
                logger.warn("Automatic Git maintenance admission failed for project {}", id, failure)
            }
        }
        if (candidates.size < scanBatch) cursor = 0
    }

    private fun submit(projectId: Long, automatic: Boolean, key: String?, callerScope: String): Long = queue.enqueue(
        GitMaintenanceTasks.TYPE, 1, mapper.writeValueAsBytes(mapOf("projectId" to projectId, "automatic" to automatic)),
        Instant.EPOCH, key, callerScope,
    ).jobId
}
