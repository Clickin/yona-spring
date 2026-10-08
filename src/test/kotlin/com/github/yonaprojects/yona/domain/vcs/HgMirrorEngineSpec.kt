package com.github.yonaprojects.yona.domain.vcs

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.domain.issue.CommitIssueReferenceService
import com.github.yonaprojects.yona.domain.issue.Issue
import com.github.yonaprojects.yona.domain.issue.IssueEventRepository
import com.github.yonaprojects.yona.domain.issue.IssueRepository
import com.github.yonaprojects.yona.domain.notification.NotificationEventRecorder
import com.github.yonaprojects.yona.domain.notification.NotificationEventRepository
import com.github.yonaprojects.yona.domain.notification.NotificationMailRepository
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.project.RepositoryMode
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import com.github.yonaprojects.yona.domain.watch.WatchService
import com.github.yonaprojects.yona.domain.webhook.WebhookService
import io.github.search5.hg4j.transport.HgRemoteConnectionFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.Pageable
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.File
import java.nio.file.Files
import java.util.UUID

// No test-wide transaction: these assertions inspect real commits and rollbacks.
class HgMirrorEngineSpec @Autowired constructor(
    private val store: RepositoryMirrorStore,
    private val mirrors: RepositoryMirrorRepository,
    private val projects: ProjectRepository,
    private val issues: IssueRepository,
    private val references: IssueEventRepository,
    private val notifications: NotificationEventRepository,
    private val mail: NotificationMailRepository,
    private val users: UserRepository,
    private val recorder: NotificationEventRecorder,
    private val authorMappings: RepositoryMirrorAuthorRepository,
    transactionManager: PlatformTransactionManager
) : AbstractIntegrationTest() {
    init {
        val transaction = TransactionTemplate(transactionManager)
        val watchers = mockk<WatchService>()
        val webhooks = mockk<WebhookService>(relaxed = true)
        val indexer = spyk(HgMirrorIndexer(MirrorCommitIndexer(store, CommitIssueReferenceService(issues, references), recorder, watchers, webhooks, authorMappings)))
        val settings = RepositoryMirrorProperties().apply {
            enabled = true
            egressRestricted = true
            nodeId = "writer"
            writerNodeId = "writer"
            proxyHost = "127.0.0.1"
            proxyPort = 3128
            allowedOrigins = listOf("https://hg.example.test")
        }
        val transport = mockk<HgMirrorTransport>()
        val engine = HgMirrorEngine(store, transport, indexer, settings)
        val worker = "hg-engine-spec"
        val fence = 3L
        lateinit var mirror: RepositoryMirror
        lateinit var project: Project
        lateinit var issue: Issue
        lateinit var receiver: User
        lateinit var work: File
        lateinit var origin: File
        lateinit var local: File
        var cancelled = false
        var source: () -> HgMirrorSource = { HgMirrorTestSupport.localSource(origin) }

        fun commit(path: String, message: String, author: String = "tester <tester@example.com>", seconds: Long? = null) =
            HgMirrorTestSupport.commit(origin, path, path, message, author, seconds)

        fun snapshot() = store.snapshot(mirror.id!!)
        fun events() = references.findByIssueOrderByCreatedAsc(issue)
        fun notices() = notifications.findByReceiver(receiver, Pageable.unpaged()).content

        fun sync() {
            engine.sync(MirrorSyncContext(mirror.id!!, worker, fence, snapshot(), local, { if (cancelled) throw MirrorLeaseLost() }, { cancelled }))
        }

        // What the synchronizer does after a clean engine run.
        fun run() {
            sync()
            store.complete(mirror.id!!, worker, fence, 60)
        }

        beforeEach {
            clearMocks(watchers, webhooks, indexer, answers = false)
            cancelled = false
            work = Files.createTempDirectory("hg-mirror-engine").toFile()
            origin = HgMirrorTestSupport.init(work.resolve("origin"))
            local = work.resolve("mirror").also { it.mkdirs() }
            source = { HgMirrorTestSupport.localSource(origin) }
            every { transport.open(any(), any()) } answers { source() }
            transaction.executeWithoutResult {
                val suffix = UUID.randomUUID().toString().take(8)
                project = projects.save(Project(name = "hg-mirror-$suffix", owner = "owner", vcs = "MERCURIAL").apply {
                    repositoryMode = RepositoryMode.MIRROR
                })
                issue = issues.save(Issue(project = project, number = 1, title = "Referenced issue"))
                receiver = users.save(User(loginId = "hg-watch-$suffix", name = "Watcher", email = "$suffix@example.test"))
                mirror = mirrors.save(RepositoryMirror(
                    project = project,
                    sourceUrl = "https://hg.example.test/repo",
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
                authorMappings.deleteAll(authorMappings.findAllByMirrorIdOrderBySourceAuthorAsc(mirror.id!!))
                mirrors.deleteById(mirror.id!!)
                issues.deleteById(issue.id!!)
                projects.deleteById(project.id!!)
                users.deleteById(receiver.id!!)
            }
            work.deleteRecursively()
        }

        describe("Mercurial mirror engine") {
            it("references the first import once without notifying, then notifies for later changesets") {
                val first = commit("a.txt", "Fix #1")
                val second = commit("b.txt", "no reference")
                val third = commit("c.txt", "Fix #1, repeat #1")
                val protocols = HgRemoteConnectionFactory.getRegisteredProtocols().toList()
                run()
                HgRemoteConnectionFactory.getRegisteredProtocols() shouldBe protocols
                events().map { it.newValue }.toSet() shouldBe setOf(first, third)
                notices().size shouldBe 0
                verify(exactly = 0) { webhooks.sendMirrorPushWebhook(any(), any()) }
                snapshot().apply {
                    initialImportTargetRevision shouldBe 2L
                    lastVerifiedRevision shouldBe 2L
                    lastIndexedRevision shouldBe 2L
                    localYoungestRevision shouldBe 2L
                    sourceRepositoryUuid shouldBe first
                    localRepositoryUuid shouldBe first
                    initialImportDone shouldBe true
                    status shouldBe RepositoryMirrorStatus.INCREMENTAL
                }
                HgMirrorChangelog(local).use { it.heads() shouldBe listOf(third) }
                second.length shouldBe 40

                val fourth = commit("d.txt", "Later fix #1")
                val fifth = commit("e.txt", "Another change")
                run()
                events().map { it.newValue }.toSet() shouldBe setOf(first, third, fourth)
                notices().size shouldBe 2
                verify(exactly = 2) { webhooks.sendMirrorPushWebhook(any(), any()) }
                snapshot().lastIndexedRevision shouldBe 4L
                HgMirrorChangelog(local).use { it.node(4) shouldBe fifth }

                // Nothing new: no more notifications or webhooks.
                run()
                notices().size shouldBe 2
                verify(exactly = 2) { webhooks.sendMirrorPushWebhook(any(), any()) }
            }

            it("resumes after a crash between revisions without duplicate references or notifications") {
                val first = commit("a.txt", "Fix #1")
                run()
                val second = commit("b.txt", "Fix #1 again")
                val third = commit("c.txt", "Third change")
                // The second revision is verified and indexed; the process dies while indexing the third.
                every { indexer.index(any(), any(), any(), 2L, any()) } throws IllegalStateException("crash")
                shouldThrow<IllegalStateException> { run() }
                snapshot().apply {
                    lastVerifiedRevision shouldBe 2L
                    lastIndexedRevision shouldBe 1L
                }
                events().map { it.newValue }.toSet() shouldBe setOf(first, second)
                notices().size shouldBe 1
                clearMocks(indexer, answers = true)
                run()
                snapshot().lastIndexedRevision shouldBe 2L
                events().map { it.newValue }.toSet() shouldBe setOf(first, second)
                notices().size shouldBe 2
                verify(exactly = 1) { webhooks.sendMirrorPushWebhook(any(), match { it.commits.single().id == third }) }
                verify(exactly = 1) { webhooks.sendMirrorPushWebhook(any(), match { it.commits.single().id == second }) }
            }

            it("resumes when a pull was applied but never observed") {
                commit("a.txt", "Fix #1")
                run()
                commit("b.txt", "second")
                // The pull happened (local repository is ahead), then the process died before observe.
                val before = snapshot()
                HgMirrorChangelog(local).use { it.count shouldBe 1 }
                val repository = io.github.search5.hg4j.lib.HgRepository(local)
                try {
                    val bundle = HgMirrorEngine.parseBundle(HgMirrorTestSupport.localSource(origin).bundle(HgMirrorChangelog(local).use { it.heads() }, HgMirrorTestSupport.localSource(origin).heads()))
                    io.github.search5.hg4j.api.PullCommand(repository).applyBundle(bundle)
                } finally { repository.close() }
                before.lastIndexedRevision shouldBe 0L
                run()
                snapshot().lastIndexedRevision shouldBe 1L
                notices().size shouldBe 1
            }

            it("follows source bookmarks: create, move and delete") {
                val first = commit("a.txt", "one")
                val second = commit("b.txt", "two")
                HgMirrorTestSupport.bookmark(origin, "main", first)
                HgMirrorTestSupport.bookmark(origin, "feature", second)
                run()
                HgMirrorTestSupport.bookmarks(local) shouldBe mapOf("main" to first, "feature" to second)
                val third = commit("c.txt", "three")
                HgMirrorTestSupport.bookmark(origin, "main", third)
                HgMirrorTestSupport.deleteBookmark(origin, "feature")
                HgMirrorTestSupport.bookmark(origin, "release", first)
                run()
                HgMirrorTestSupport.bookmarks(local) shouldBe mapOf("main" to third, "release" to first)
                // A bookmark moved without any new changeset is followed too.
                HgMirrorTestSupport.bookmark(origin, "main", first)
                run()
                HgMirrorTestSupport.bookmarks(local) shouldBe HgMirrorTestSupport.bookmarks(origin)
                notices().size shouldBe 1
            }

            it("follows bookmarks with unusual names on every run and skips ones Mercurial cannot store") {
                val first = commit("a.txt", "one")
                val names = listOf("기능/50% off", "a+b", "back\\slash", "with space")
                for (name in names) HgMirrorTestSupport.bookmark(origin, name, first)
                run()
                HgMirrorTestSupport.bookmarks(local).keys shouldBe HgMirrorTestSupport.bookmarks(origin).keys
                run()
                run()
                HgMirrorTestSupport.bookmarks(local) shouldBe HgMirrorTestSupport.bookmarks(origin)
                // A source bookmark whose node the mirror lacks, and a control character name, are skipped without failing.
                source = {
                    val inner = HgMirrorTestSupport.localSource(origin)
                    object : HgMirrorSource by inner {
                        override fun bookmarks() = inner.bookmarks() + mapOf("ghost" to "9".repeat(40), "bad\u0007name" to first)
                    }
                }
                run()
                HgMirrorTestSupport.bookmarks(local).keys shouldBe names.toSet()
            }

            it("completes an empty source without cursors and notifies its first later changeset") {
                run()
                snapshot().apply {
                    initialImportDone shouldBe true
                    status shouldBe RepositoryMirrorStatus.INCREMENTAL
                    initialImportTargetRevision shouldBe null
                    sourceRepositoryUuid shouldBe null
                    lastVerifiedRevision shouldBe -1L
                    lastIndexedRevision shouldBe -1L
                }
                local.resolve(".hg").isDirectory shouldBe true
                run()
                val first = commit("a.txt", "Fix #1")
                run()
                snapshot().apply {
                    initialImportTargetRevision shouldBe 0L
                    sourceRepositoryUuid shouldBe first
                    lastIndexedRevision shouldBe 0L
                }
                notices().size shouldBe 1
                events().single().newValue shouldBe first
                verify(exactly = 1) { webhooks.sendMirrorPushWebhook(any(), any()) }
            }

            it("refuses a source that went back to empty or whose root changed, before fetching anything") {
                val first = commit("a.txt", "one")
                run()
                var fetched = 0
                // Same name, unrelated history.
                val other = HgMirrorTestSupport.init(work.resolve("other"))
                HgMirrorTestSupport.commit(other, "z.txt", "z", "unrelated")
                source = {
                    val inner = HgMirrorTestSupport.localSource(other)
                    object : HgMirrorSource by inner {
                        override fun bundle(common: List<String>, heads: List<String>): ByteArray { fetched++; return inner.bundle(common, heads) }
                    }
                }
                shouldThrow<MirrorFailure> { sync() }.apply {
                    code shouldBe "SOURCE_IDENTITY_CHANGED"
                    attention shouldBe true
                }
                fetched shouldBe 0
                HgMirrorChangelog(local).use { it.count shouldBe 1 }
                // Recorded identity of a different root.
                transaction.executeWithoutResult { mirrors.findById(mirror.id!!).orElseThrow().sourceRepositoryUuid = "1".repeat(40) }
                source = { HgMirrorTestSupport.localSource(origin) }
                shouldThrow<MirrorFailure> { sync() }.code shouldBe "SOURCE_IDENTITY_CHANGED"
                transaction.executeWithoutResult { mirrors.findById(mirror.id!!).orElseThrow().sourceRepositoryUuid = first }
                // The source lost everything.
                source = { HgMirrorTestSupport.localSource(HgMirrorTestSupport.init(work.resolve("emptied"))) }
                shouldThrow<MirrorFailure> { sync() }.attention shouldBe true
            }

            it("refuses rewritten source history that no longer contains local heads") {
                commit("a.txt", "one")
                commit("b.txt", "two")
                run()
                source = {
                    val inner = HgMirrorTestSupport.localSource(origin)
                    object : HgMirrorSource by inner {
                        override fun known(nodes: List<String>) = nodes.map { false }
                    }
                }
                shouldThrow<MirrorFailure> { sync() }.apply {
                    code shouldBe "REVISION_HISTORY_CHANGED"
                    attention shouldBe true
                }
                snapshot().lastIndexedRevision shouldBe 1L
            }

            it("rejects a non-empty directory that is not a Mercurial repository and leaves it untouched") {
                commit("a.txt", "one")
                local.resolve("keep.txt").writeText("operator data")
                shouldThrow<MirrorFailure> { sync() }.apply {
                    code shouldBe "TARGET_INCOMPLETE"
                    attention shouldBe true
                }
                local.list()!!.toList() shouldBe listOf("keep.txt")
                // A recorded local identity means the repository must already exist.
                local.resolve("keep.txt").delete()
                transaction.executeWithoutResult { mirrors.findById(mirror.id!!).orElseThrow().localRepositoryUuid = "2".repeat(40) }
                shouldThrow<MirrorFailure> { sync() }.code shouldBe "TARGET_INCOMPLETE"
                local.list()!!.size shouldBe 0
            }

            it("stops on cancellation and requires activation before any connection") {
                commit("a.txt", "one")
                cancelled = true
                shouldThrow<MirrorLeaseLost> { sync() }
                snapshot().lastVerifiedRevision shouldBe -1L
                cancelled = false
                settings.enabled = false
                try {
                    clearMocks(transport, answers = false)
                    shouldThrow<MirrorFailure> { sync() }.code shouldBe "FEATURE_DISABLED"
                    verify(exactly = 0) { transport.open(any(), any()) }
                } finally { settings.enabled = true }
            }

            it("reads author keys locally for the author mapping screen") {
                val first = commit("a.txt", "one", "원본 <b@example.com>")
                commit("b.txt", "two", "Second <a@example.com>")
                commit("c.txt", "three", "no-email-author")
                commit("d.txt", "four", "Again <b@example.com>")
                run()
                // The source is gone: these reads never touch the network.
                every { transport.open(any(), any()) } throws AssertionError("network")
                val state = snapshot()
                engine.authorKeyOf(state, local, first) shouldBe "b@example.com"
                engine.authorKeyOf(state, local, first.uppercase()) shouldBe "b@example.com"
                engine.authorKeyOf(state, local, "0".repeat(40)) shouldBe null
                engine.authorKeyOf(state, local, "not-a-node") shouldBe null
                HgMirrorChangelog(local).use { engine.authorKeyOf(state, local, it.node(2)) shouldBe null }
                engine.authorKeys(state, local, 10) shouldBe listOf("a@example.com", "b@example.com")
                engine.authorKeys(state, local, 1).size shouldBe 1
                engine.authorKeys(state, work.resolve("missing"), 10) shouldBe emptyList()
                // Commits beyond the verified cursor are not exposed.
                state.lastVerifiedRevision = 0
                engine.authorKeys(state, local, 10) shouldBe listOf("b@example.com")
                HgMirrorChangelog(local).use { engine.authorKeyOf(state, local, it.node(1)) shouldBe null }
            }

            it("records the message, author and author time of a changeset exactly") {
                commit("a.txt", "Multi\nline #1\n\nbody", "Full Name <full@example.com>", 1_700_000_000L)
                run()
                HgMirrorChangelog(local).use { log ->
                    val commit = log.commit(0)
                    commit.message shouldBe "Multi\nline #1\n\nbody"
                    commit.authorName shouldBe "Full Name"
                    commit.authorEmail shouldBe "full@example.com"
                    commit.authorKey shouldBe "full@example.com"
                    commit.timestamp.epochSecond shouldBe 1_700_000_000L
                    commit.id shouldBe log.node(0)
                }
                events().single().created.epochSecond shouldBe 1_700_000_000L
            }
        }
    }
}
