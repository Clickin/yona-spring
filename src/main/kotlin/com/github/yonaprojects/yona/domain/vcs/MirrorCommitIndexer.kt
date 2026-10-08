package com.github.yonaprojects.yona.domain.vcs

import com.github.yonaprojects.yona.domain.enumeration.EventType
import com.github.yonaprojects.yona.domain.enumeration.ResourceType
import com.github.yonaprojects.yona.domain.issue.CommitIssueReferenceService
import com.github.yonaprojects.yona.domain.issue.CommitReference
import com.github.yonaprojects.yona.domain.notification.NotificationEvent
import com.github.yonaprojects.yona.domain.notification.NotificationEventRecorder
import com.github.yonaprojects.yona.domain.watch.WatchService
import com.github.yonaprojects.yona.domain.webhook.PushedVcsCommit
import com.github.yonaprojects.yona.domain.webhook.PushedVcsCommits
import com.github.yonaprojects.yona.domain.webhook.WebhookService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Records mirrored commits for every VCS. Issue references, the NEW_COMMIT notification and the engine's
 * cursor change commit in one fenced transaction: a rollback leaves all of them out and the next run
 * resumes from the unchanged cursor. Webhooks are sent after commit on a best-effort basis.
 */
@Component
class MirrorCommitIndexer(
    private val store: RepositoryMirrorStore,
    private val references: CommitIssueReferenceService,
    private val notificationEventRecorder: NotificationEventRecorder,
    private val watchService: WatchService,
    private val webhookService: WebhookService,
    private val authorMappings: RepositoryMirrorAuthorRepository
) {
    private val logger = LoggerFactory.getLogger(MirrorCommitIndexer::class.java)

    /**
     * [accept] runs first on the fresh row and returns false when the batch was already indexed; it throws
     * to reject an invalid batch. [checkpoint] records the new cursor position on the same row (and may
     * write engine-specific cursor rows, which join this transaction). [notify] decides on the fresh row
     * whether the batch is past the first import; linear engines compare against the fixed target R.
     */
    fun index(
        id: Long,
        owner: String,
        fence: Long,
        commits: List<MirrorCommit>,
        refNames: List<String> = emptyList(),
        accept: (RepositoryMirror) -> Boolean = { true },
        notify: (RepositoryMirror) -> Boolean = { it.initialImportDone },
        checkpoint: (RepositoryMirror) -> Unit
    ) {
        store.fenced(id, owner, fence) { mirror ->
            mirror.checkCursors()
            if (!accept(mirror)) return@fenced
            val project = mirror.project
            // Decide before the checkpoint: the batch that completes the first import does not notify.
            val notifies = notify(mirror)
            for (commit in commits) {
                // Only an administrator-confirmed mapping names a Yona account; a matching name does not.
                val sender = commit.authorKey?.let { authorMappings.findUser(id, it) }
                references.record(project, CommitReference(commit.id, commit.message, sender, commit.timestamp))
            }
            checkpoint(mirror)
            if (!notifies || commits.isEmpty()) return@fenced

            val title = "[${project.name}] ${commits.size}개의 커밋이 미러에 반영되었습니다."
            val event = NotificationEvent(
                title = title,
                senderId = null,
                created = commits.maxOf { it.timestamp },
                // Commit IDs are not unique across projects (SVN revision numbers): target the project.
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
            // Never draft-merge: numeric SVN ids can coincide across projects.
            notificationEventRecorder.record(event, skipWaypoint = false)
            val pushed = PushedVcsCommits(
                commits.map { PushedVcsCommit(it.id, it.message, it.authorName ?: it.authorKey ?: "", it.authorEmail, it.timestamp) },
                refNames
            )
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    try {
                        webhookService.sendMirrorPushWebhook(project, pushed)
                    } catch (_: Exception) {
                        // Best effort only. Never persist secrets or rewind a committed cursor.
                        logger.warn("Mirror webhook delivery failed for mirror {}", id)
                    }
                }
            })
        }
    }
}
