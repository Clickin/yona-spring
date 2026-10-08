package com.github.yonaprojects.yona.domain.vcs

import com.github.yonaprojects.yona.domain.enumeration.EventType
import com.github.yonaprojects.yona.domain.enumeration.ResourceType
import com.github.yonaprojects.yona.domain.issue.IssueEvent
import com.github.yonaprojects.yona.domain.issue.IssueEventRepository
import com.github.yonaprojects.yona.domain.issue.IssueReferenceParser
import com.github.yonaprojects.yona.domain.issue.IssueRepository
import com.github.yonaprojects.yona.domain.notification.NotificationEvent
import com.github.yonaprojects.yona.domain.notification.NotificationEventRecorder
import com.github.yonaprojects.yona.domain.watch.WatchService
import com.github.yonaprojects.yona.domain.webhook.SvnMirrorCommit
import com.github.yonaprojects.yona.domain.webhook.WebhookService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.tmatesoft.svn.core.SVNProperties
import org.tmatesoft.svn.core.SVNRevisionProperty

@Component
class SvnMirrorIndexer(
    private val store: RepositoryMirrorStore,
    private val issueRepository: IssueRepository,
    private val issueEventRepository: IssueEventRepository,
    private val notificationEventRecorder: NotificationEventRecorder,
    private val watchService: WatchService,
    private val webhookService: WebhookService
) {
    private val logger = LoggerFactory.getLogger(SvnMirrorIndexer::class.java)

    fun index(id: Long, owner: String, fence: Long, revision: Long, properties: SVNProperties) {
        store.fenced(id, owner, fence) { mirror ->
            mirror.checkCursors()
            val fixedR = mirror.initialImportTargetRevision
                ?: throw MirrorFailure("INVALID_CHECKPOINT", attention = true)
            if (mirror.sourceRepositoryUuid.isNullOrBlank() || mirror.generation < 1 || revision < 0) {
                throw MirrorFailure("INVALID_CHECKPOINT", attention = true)
            }
            if (revision <= mirror.lastIndexedRevision) return@fenced
            if (revision > mirror.lastVerifiedRevision ||
                (revision != 0L && revision != maxOf(1L, mirror.lastIndexedRevision + 1)) ||
                (revision == 0L && mirror.lastIndexedRevision != -1L)) {
                throw MirrorFailure("INVALID_INDEX_REVISION", attention = true)
            }
            if (revision == 0L) {
                mirror.lastIndexedRevision = 0
                return@fenced
            }

            val date = svnMirrorRevisionDate(properties)
            val author = properties.getStringValue(SVNRevisionProperty.AUTHOR)
            val message = properties.getStringValue(SVNRevisionProperty.LOG) ?: ""
            val commitId = revision.toString()
            val project = mirror.project
            for (number in IssueReferenceParser.findReferredIssueNumbers(message)) {
                val issue = issueRepository.findByProjectAndNumber(project, number) ?: continue
                issueEventRepository.save(IssueEvent(
                    issue = issue,
                    senderLoginId = author,
                    senderEmail = null,
                    newValue = commitId,
                    created = date,
                    eventType = EventType.ISSUE_REFERRED_FROM_COMMIT
                ))
            }

            if (revision > fixedR) {
                val title = "[${project.name}] SVN r$revision"
                val event = NotificationEvent(
                    title = title,
                    senderId = null,
                    created = date,
                    resourceType = ResourceType.PROJECT,
                    resourceId = project.id.toString(),
                    eventType = EventType.NEW_COMMIT,
                    newValue = title,
                    receivers = watchService.findActualWatchers(
                        baseWatchers = emptySet(),
                        resourceType = ResourceType.PROJECT,
                        resourceId = project.id.toString(),
                        projectId = project.id,
                        eventType = EventType.NEW_COMMIT
                    ).toMutableSet()
                )
                // REQUIRED joins the fenced transaction; recorder failures must escape.
                // Numeric SVN ids can coincide across projects: never draft-merge commits.
                notificationEventRecorder.record(event, skipWaypoint = false)
                val commit = SvnMirrorCommit(commitId, author, date, message)
                TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                    override fun afterCommit() {
                        try {
                            webhookService.sendSvnMirrorWebhook(project, commit)
                        } catch (_: Exception) {
                            // Best effort only. Never persist secrets or rewind a committed cursor.
                            logger.warn("SVN mirror webhook delivery failed for mirror {} revision {}", id, revision)
                        }
                    }
                })
            }
            mirror.lastIndexedRevision = revision
        }
    }
}
