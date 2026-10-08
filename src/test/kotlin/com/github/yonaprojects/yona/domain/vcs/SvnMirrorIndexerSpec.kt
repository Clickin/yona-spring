package com.github.yonaprojects.yona.domain.vcs

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.domain.enumeration.EventType
import com.github.yonaprojects.yona.domain.enumeration.ResourceType
import com.github.yonaprojects.yona.domain.issue.Issue
import com.github.yonaprojects.yona.domain.issue.IssueEventRepository
import com.github.yonaprojects.yona.domain.issue.IssueRepository
import com.github.yonaprojects.yona.domain.notification.NotificationEventRecorder
import com.github.yonaprojects.yona.domain.notification.NotificationEventRepository
import com.github.yonaprojects.yona.domain.notification.NotificationMailRepository
import com.github.yonaprojects.yona.domain.notification.NotificationMailRenderer
import com.github.yonaprojects.yona.domain.notification.NotificationUrlResolver
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.project.RepositoryMode
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import com.github.yonaprojects.yona.domain.watch.WatchService
import com.github.yonaprojects.yona.domain.webhook.SvnMirrorCommit
import com.github.yonaprojects.yona.domain.webhook.WebhookService
import com.github.yonaprojects.yona.web.IndexController
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jsoup.Jsoup
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.Pageable
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.ui.ExtendedModelMap
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.tmatesoft.svn.core.SVNProperties
import org.tmatesoft.svn.core.SVNRevisionProperty
import java.time.Instant
import java.net.URI
import java.util.Locale
import java.util.UUID

// No test-wide transaction: these assertions inspect real commits and rollbacks.
class SvnMirrorIndexerSpec @Autowired constructor(
    private val store: RepositoryMirrorStore,
    private val mirrors: RepositoryMirrorRepository,
    private val projects: ProjectRepository,
    private val issues: IssueRepository,
    private val references: IssueEventRepository,
    private val notifications: NotificationEventRepository,
    private val mail: NotificationMailRepository,
    private val users: UserRepository,
    private val recorder: NotificationEventRecorder,
    private val inbox: IndexController,
    private val notificationUrls: NotificationUrlResolver,
    private val mailRenderer: NotificationMailRenderer,
    transactionManager: PlatformTransactionManager
) : AbstractIntegrationTest() {
    init {
        val transaction = TransactionTemplate(transactionManager)
        val independent = TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }
        val watchers = mockk<WatchService>()
        val webhooks = mockk<WebhookService>(relaxed = true)
        val indexer = SvnMirrorIndexer(store, issues, references, recorder, watchers, webhooks)
        val date = Instant.parse("2007-05-06T07:08:09Z")
        val author = "외부-svn-author"
        val worker = "indexer-spec"
        val fence = 7L
        lateinit var mirror: RepositoryMirror
        lateinit var project: Project
        lateinit var issue: Issue
        lateinit var receiver: User

        fun properties(message: String = "Fix #1, repeat #1; unknown #999", name: String? = author) =
            SVNProperties().apply {
                name?.let { put(SVNRevisionProperty.AUTHOR, it) }
                put(SVNRevisionProperty.DATE, date.toString())
                put(SVNRevisionProperty.LOG, message)
                put("custom:source", "preserved by replication, ignored by indexing")
            }

        fun index(revision: Long, props: SVNProperties = properties()) =
            indexer.index(mirror.id!!, worker, fence, revision, props)

        fun events() = references.findByIssueOrderByCreatedAsc(issue)
        fun notices() = notifications.findByReceiver(receiver, Pageable.unpaged()).content
        fun cursor() = store.snapshot(mirror.id!!).lastIndexedRevision

        beforeEach {
            clearMocks(watchers, webhooks)
            transaction.executeWithoutResult {
                val suffix = UUID.randomUUID().toString().take(8)
                project = projects.save(Project(name = "svn-index-$suffix", owner = "owner", vcs = "SUBVERSION").apply {
                    repositoryMode = RepositoryMode.MIRROR
                })
                issue = issues.save(Issue(project = project, number = 1, title = "Referenced issue"))
                receiver = users.save(User(loginId = "svn-watch-$suffix", name = "Watcher", email = "$suffix@example.test"))
                mirror = mirrors.save(RepositoryMirror(
                    project = project,
                    sourceUrl = "https://svn.example.test/repository",
                    sourceRepositoryUuid = "source-uuid",
                    localRepositoryUuid = "local-uuid",
                    initialImportTargetRevision = 1,
                    lastVerifiedRevision = 3,
                    localYoungestRevision = 3,
                    sourceYoungestRevision = 3,
                    leaseOwner = worker,
                    leaseUntil = store.databaseNow().plusSeconds(3600),
                    fence = fence
                ))
            }
            every { watchers.findActualWatchers(any(), any(), any(), any(), any(), any()) } answers {
                setOf(users.findById(receiver.id!!).orElseThrow())
            }
        }

        afterEach {
            transaction.executeWithoutResult {
                notices().forEach { event ->
                    mail.findByNotificationEvent(event)?.let { mail.delete(it) }
                    notifications.delete(event)
                }
                references.deleteAll(events())
                mirrors.deleteById(mirror.id!!)
                issues.deleteById(issue.id!!)
                projects.deleteById(project.id!!)
                users.deleteById(receiver.id!!)
            }
        }

        describe("verified SVN revision indexing") {
            it("r0 only advances the cursor, even with a log and no date") {
                index(0, SVNProperties().apply { put(SVNRevisionProperty.LOG, "#1") })
                cursor() shouldBe 0L
                events().size shouldBe 0
                notices().size shouldBe 0
                verify(exactly = 0) { webhooks.sendSvnMirrorWebhook(any(), any()) }
                verify(exactly = 0) { watchers.findActualWatchers(any(), any(), any(), any(), any(), any()) }
            }

            it("an initially empty source notifies its first later revision and advances even without references") {
                transaction.executeWithoutResult {
                    mirrors.findById(mirror.id!!).orElseThrow().initialImportTargetRevision = 0
                }
                index(0)
                index(1, properties("No issue references"))
                events().size shouldBe 0
                notices().single().resourceType shouldBe ResourceType.PROJECT
                notices().single().resourceId shouldBe project.id.toString()
                cursor() shouldBe 1L
                store.snapshot(mirror.id!!).initialImportTargetRevision shouldBe 0L
                verify(exactly = 1) { webhooks.sendSvnMirrorWebhook(any(), any()) }
            }

            it("requires a source identity and positive generation before indexing") {
                transaction.executeWithoutResult {
                    mirrors.findById(mirror.id!!).orElseThrow().sourceRepositoryUuid = null
                }
                shouldThrow<MirrorFailure> { index(1) }.attention shouldBe true
                transaction.executeWithoutResult {
                    mirrors.findById(mirror.id!!).orElseThrow().apply {
                        sourceRepositoryUuid = "source-uuid"
                        generation = 0
                    }
                }
                shouldThrow<MirrorFailure> { index(1) }.attention shouldBe true
                cursor() shouldBe -1L
                events().size shouldBe 0
                notices().size shouldBe 0
            }

            it("records distinct project-local references with original metadata and suppresses initial notifications") {
                val userCount = users.count()
                index(0)
                index(1)
                val event = events().single()
                event.senderLoginId shouldBe author
                event.senderEmail shouldBe null
                event.created shouldBe date
                event.newValue shouldBe "1"
                event.eventType shouldBe EventType.ISSUE_REFERRED_FROM_COMMIT
                users.count() shouldBe userCount
                cursor() shouldBe 1L
                notices().size shouldBe 0
                store.snapshot(mirror.id!!).initialImportTargetRevision shouldBe 1L
                verify(exactly = 0) { webhooks.sendSvnMirrorWebhook(any(), any()) }
            }

            it("initial R remains the cutoff when source HEAD grows and revisions repeat") {
                index(1)
                index(1, properties("changed #1", "different author"))
                index(2)
                index(2)
                events().map { it.newValue }.sortedBy { it } shouldBe listOf("1", "2")
                val notification = notices().single()
                notification.resourceType shouldBe ResourceType.PROJECT
                notification.resourceId shouldBe project.id.toString()
                notification.title shouldBe "[${project.name}] SVN r2"
                notification.eventType shouldBe EventType.NEW_COMMIT
                notification.senderId shouldBe null
                notification.created shouldBe date
                transaction.execute { mail.findByNotificationEvent(notification) != null } shouldBe true
                store.snapshot(mirror.id!!).initialImportTargetRevision shouldBe 1L
                cursor() shouldBe 2L
                verify(exactly = 1) { webhooks.sendSvnMirrorWebhook(any(), SvnMirrorCommit("2", author, date, properties().getStringValue(SVNRevisionProperty.LOG))) }
            }

            it("renders project commit links in inbox and mail for a persisted post-import SVN notification") {
                index(1)
                notices().size shouldBe 0
                index(2)
                val notification = notices().single()
                val expectedPath = "/${project.owner}/${project.name}/commits"
                val model = ExtendedModelMap()
                val authentication = UsernamePasswordAuthenticationToken(receiver.loginId, null)

                inbox.notifications(authentication, model) shouldBe "index/notifications"
                val inboxNotification = (model["notifications"] as List<*>)
                    .filterIsInstance<IndexController.NotificationViewDto>().single()
                inboxNotification.id shouldBe notification.id
                inboxNotification.url shouldBe expectedPath

                val mailUrl = notificationUrls.getUrlToView(notification)
                URI(requireNotNull(mailUrl)).path shouldBe expectedPath
                val html = mailRenderer.render(
                    notification.newValue.orEmpty(), mailUrl, notification.resourceType,
                    notification.resourceId, false, Locale.ENGLISH
                )
                Jsoup.parse(html).select("a[href]").count {
                    URI(it.attr("href")).path == expectedPath
                } shouldBe 1
                transaction.execute { mail.findByNotificationEvent(notification) != null } shouldBe true
            }

            it("allows a missing author without fabricating an account") {
                index(1, properties(name = null))
                events().single().senderLoginId shouldBe null
                events().single().senderEmail shouldBe null
            }

            it("rejects a gap and an unverified revision without touching references or cursor") {
                shouldThrow<MirrorFailure> { index(2) }.code shouldBe "INVALID_INDEX_REVISION"
                index(1)
                index(2)
                index(3)
                shouldThrow<MirrorFailure> { index(4) }.code shouldBe "INVALID_INDEX_REVISION"
                cursor() shouldBe 3L
                events().size shouldBe 3
            }

            it("rejects stale fences, wrong owners, expired leases and paused mirrors before even a duplicate") {
                index(1)
                shouldThrow<MirrorLeaseLost> { indexer.index(mirror.id!!, worker, fence - 1, 1, properties()) }
                shouldThrow<MirrorLeaseLost> { indexer.index(mirror.id!!, "stale-owner", fence, 2, properties()) }
                transaction.executeWithoutResult {
                    mirrors.findById(mirror.id!!).orElseThrow().leaseUntil = store.databaseNow().minusSeconds(1)
                }
                shouldThrow<MirrorLeaseLost> { index(2) }
                store.pause(mirror.id!!)
                shouldThrow<MirrorLeaseLost> { index(1) }
                cursor() shouldBe 1L
                events().size shouldBe 1
                notices().size shouldBe 0
            }

            it("rolls back references, notification, mail marker and cursor together and never sends before commit") {
                index(1)
                shouldThrow<IllegalStateException> {
                    transaction.executeWithoutResult {
                        index(2)
                        events().size shouldBe 2
                        notices().size shouldBe 1
                        cursor() shouldBe 2L
                        verify(exactly = 0) { webhooks.sendSvnMirrorWebhook(any(), any()) }
                        error("injected interruption before commit")
                    }
                }
                cursor() shouldBe 1L
                events().size shouldBe 1
                notices().size shouldBe 0
                verify(exactly = 0) { webhooks.sendSvnMirrorWebhook(any(), any()) }
                index(2)
                cursor() shouldBe 2L
                events().size shouldBe 2
                notices().size shouldBe 1
            }

            it("propagates recorder failures after notification insertion so the fenced transaction rolls back") {
                index(1)
                val failedMail = mockk<NotificationMailRepository>()
                every { failedMail.save(any()) } throws IllegalStateException("injected mail marker failure")
                val failedRecorder = NotificationEventRecorder(notifications, failedMail, SimpleMeterRegistry())
                val failedIndexer = SvnMirrorIndexer(store, issues, references, failedRecorder, watchers, webhooks)
                shouldThrow<IllegalStateException> {
                    failedIndexer.index(mirror.id!!, worker, fence, 2, properties())
                }
                cursor() shouldBe 1L
                events().size shouldBe 1
                notices().size shouldBe 0
                verify(exactly = 0) { webhooks.sendSvnMirrorWebhook(any(), any()) }
            }

            it("sends only after durable commit and webhook failure never rewinds or duplicates local data") {
                index(1)
                var committedCursor: Long? = null
                var committedNotices: Int? = null
                every { webhooks.sendSvnMirrorWebhook(any(), any()) } answers {
                    independent.executeWithoutResult {
                        committedCursor = cursor()
                        committedNotices = notices().size
                    }
                    throw IllegalStateException("remote endpoint unavailable")
                }
                index(2)
                committedCursor shouldBe 2L
                committedNotices shouldBe 1
                index(2)
                cursor() shouldBe 2L
                events().size shouldBe 2
                notices().size shouldBe 1
                verify(exactly = 1) { webhooks.sendSvnMirrorWebhook(any(), any()) }
            }

            it("keeps recorder empty-recipient semantics while still requesting the incremental webhook") {
                every { watchers.findActualWatchers(any(), any(), any(), any(), any(), any()) } returns emptySet()
                index(1)
                index(2)
                notices().size shouldBe 0
                cursor() shouldBe 2L
                verify(exactly = 1) { webhooks.sendSvnMirrorWebhook(any(), any()) }
            }

            it("never substitutes indexing time for a missing or malformed original date") {
                for (props in listOf(SVNProperties(), properties().apply { put(SVNRevisionProperty.DATE, "bad-date") })) {
                    val failure = shouldThrow<MirrorFailure> { index(1, props) }
                    failure.code shouldBe "INVALID_REVISION_DATE"
                    failure.attention shouldBe true
                }
                cursor() shouldBe -1L
                events().size shouldBe 0
            }
        }
    }
}
