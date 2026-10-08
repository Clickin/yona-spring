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
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.transport.HttpTransport
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.TagOpt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.nio.file.Files
import java.util.Date
import java.util.TimeZone
import java.util.UUID
import jakarta.persistence.EntityManager

// No test-wide transaction: these assertions inspect real commits and rollbacks.
class GitMirrorEngineSpec @Autowired constructor(
    private val store: RepositoryMirrorStore,
    private val mirrors: RepositoryMirrorRepository,
    private val refRows: RepositoryMirrorRefRepository,
    private val projects: ProjectRepository,
    private val issues: IssueRepository,
    private val references: IssueEventRepository,
    private val notifications: NotificationEventRepository,
    private val mail: NotificationMailRepository,
    private val users: UserRepository,
    private val recorder: NotificationEventRecorder,
    private val authorMappings: RepositoryMirrorAuthorRepository,
    private val entityManager: EntityManager,
    transactionManager: PlatformTransactionManager
) : AbstractIntegrationTest() {
    /** A local source repository that stands in for the remote. */
    private class Source(val dir: File) {
        val git: Git = Git.init().setDirectory(dir).setInitialBranch("main").call()
        private var clock = 1_700_000_000_000L
        fun commit(message: String, email: String = "dev@example.test", name: String = "Dev"): RevCommit {
            val who = PersonIdent(name, email, Date(clock), TimeZone.getTimeZone("UTC"))
            clock += 1000
            return git.commit().setMessage(message).setAllowEmpty(true).setSign(false).setAuthor(who).setCommitter(who).call()
        }
        fun checkout(name: String, create: Boolean = false, from: RevCommit? = null) {
            val command = git.checkout().setName(name).setCreateBranch(create)
            from?.let { command.setStartPoint(it) }
            command.call()
        }
        fun resetTo(commit: RevCommit) { git.reset().setMode(ResetCommand.ResetType.HARD).setRef(commit.name).call() }
        fun ref(name: String, commit: RevCommit) {
            val update = git.repository.updateRef(name)
            update.setNewObjectId(commit)
            update.setForceUpdate(true)
            check(update.update() in setOf(RefUpdate.Result.NEW, RefUpdate.Result.FORCED))
        }
    }

    init {
        val transaction = TransactionTemplate(transactionManager)
        val watchers = mockk<WatchService>()
        val webhooks = mockk<WebhookService>(relaxed = true)
        val worker = "git-engine-spec"
        val fence = 3L
        val commitIndexer = MirrorCommitIndexer(store, CommitIssueReferenceService(issues, references), recorder, watchers, webhooks, authorMappings)
        val indexer = GitMirrorIndexer(commitIndexer, entityManager)
        val policy = mockk<MirrorSourcePolicy>(relaxed = true)

        fun properties(batch: Int) = RepositoryMirrorProperties().apply {
            enabled = true
            egressRestricted = true
            nodeId = "writer"
            writerNodeId = "writer"
            proxyHost = "127.0.0.1"
            proxyPort = 3128
            allowedOrigins = listOf("https://git.example.test")
            batchSize = batch
        }
        fun engineOf(batch: Int, git: GitMirrorIndexer = indexer) = GitMirrorEngine(policy, git, refRows, properties(batch))

        lateinit var project: Project
        lateinit var mirror: RepositoryMirror
        lateinit var receiver: User
        lateinit var workDir: File
        lateinit var source: Source
        lateinit var local: File
        val issueIds = mutableListOf<Long>()

        fun localSeam(engine: GitMirrorEngine) = engine.also {
            it.fetcher = { git, _ ->
                git.fetch().setRemote(source.dir.absolutePath)
                    .setRefSpecs(RefSpec("+refs/heads/*:refs/heads/*"), RefSpec("+refs/tags/*:refs/tags/*"))
                    .setRemoveDeletedRefs(true).setTagOpt(TagOpt.NO_TAGS).call()
            }
        }
        fun context(directory: File = local) = MirrorSyncContext(
            mirror.id!!, worker, fence, store.snapshot(mirror.id!!), directory,
            { store.fenced(mirror.id!!, worker, fence) { } }, { false }
        )
        fun sync(engine: GitMirrorEngine) {
            engine.sync(context())
            store.complete(mirror.id!!, worker, fence, 60)
        }
        fun events() = transaction.execute { issues.findAll().filter { it.project.id == project.id }.flatMap { references.findByIssueOrderByCreatedAsc(it) } }!!
        fun referenced() = events().map { it.newValue }
        fun notices() = notifications.findByReceiver(receiver, org.springframework.data.domain.Pageable.unpaged()).content
        fun allRows() = refRows.findAllByMirrorId(mirror.id!!).associate { it.refName to it.commitId }
        fun rows() = allRows().filterKeys { !it.startsWith("mirror-retired/") }
        fun retired() = allRows().filterKeys { it.startsWith("mirror-retired/") }.values.toSet()
        fun ids(vararg commits: RevCommit) = commits.map { it.name }

        beforeEach {
            clearMocks(watchers, webhooks, policy)
            workDir = Files.createTempDirectory("git-mirror-spec").toFile()
            source = Source(workDir.resolve("source"))
            local = workDir.resolve("local.git").also { it.mkdirs() }
            transaction.executeWithoutResult {
                val suffix = UUID.randomUUID().toString().take(8)
                project = projects.save(Project(name = "git-mirror-$suffix", owner = "owner", vcs = "GIT").apply {
                    repositoryMode = RepositoryMode.MIRROR
                })
                issueIds += issues.save(Issue(project = project, number = 1, title = "Referenced issue")).id!!
                receiver = users.save(User(loginId = "git-watch-$suffix", name = "Watcher", email = "$suffix@example.test"))
                mirror = mirrors.save(RepositoryMirror(
                    project = project,
                    sourceUrl = "https://git.example.test/owner/repo.git",
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
                issues.findAll().filter { it.project.id == project.id }.forEach { issue ->
                    references.deleteAll(references.findByIssueOrderByCreatedAsc(issue))
                    issues.delete(issue)
                }
                refRows.deleteAll(refRows.findAllByMirrorId(mirror.id!!))
                authorMappings.deleteAll(authorMappings.findAllByMirrorIdOrderBySourceAuthorAsc(mirror.id!!))
                mirrors.deleteById(mirror.id!!)
                projects.deleteById(project.id!!)
                users.deleteById(receiver.id!!)
            }
            source.git.close()
            workDir.deleteRecursively()
        }

        describe("Git mirror engine") {
            it("references the first import once without notifying, then notifies once per batch with a webhook after commit") {
                val engine = localSeam(engineOf(2))
                val c1 = source.commit("c1 #1")
                val c2 = source.commit("c2 #1")
                val c3 = source.commit("c3 #1")
                source.ref("refs/heads/feature", c1)
                source.ref("refs/tags/v0", c2)
                sync(engine)
                referenced() shouldContainExactlyInAnyOrder ids(c1, c2, c3)
                notices().size shouldBe 0
                verify(exactly = 0) { webhooks.sendMirrorPushWebhook(any(), any()) }
                rows() shouldBe mapOf("refs/heads/main" to c3.name, "refs/heads/feature" to c1.name, "refs/tags/v0" to c2.name)
                store.snapshot(mirror.id!!).let {
                    it.initialImportDone shouldBe true
                    it.status shouldBe RepositoryMirrorStatus.INCREMENTAL
                    it.lastVerifiedRevision shouldBe -1L
                    it.lastIndexedRevision shouldBe -1L
                }

                val m1 = source.commit("m1 #1")
                val m2 = source.commit("m2 #1")
                val m3 = source.commit("m3 #1")
                sync(engine)
                referenced() shouldContainExactlyInAnyOrder ids(c1, c2, c3, m1, m2, m3)
                notices().size shouldBe 2
                val pushed = mutableListOf<com.github.yonaprojects.yona.domain.webhook.PushedVcsCommits>()
                verify(exactly = 2) { webhooks.sendMirrorPushWebhook(any(), capture(pushed)) }
                pushed.map { push -> push.commits.map { it.id } } shouldContainExactly listOf(ids(m1, m2), ids(m3))
                pushed.flatMap { it.refNames }.toSet() shouldBe setOf("refs/heads/main")
                rows()["refs/heads/main"] shouldBe m3.name
                sync(engine)
                referenced().size shouldBe 6
                notices().size shouldBe 2
            }

            it("maps commit fields from the author identity") {
                source.commit("first #1", email = "Case.Sensitive@Example.Test", name = "작성자")
                sync(localSeam(engineOf(5)))
                val d = source.commit("third\n\nbody", email = "late@example.test")
                val e = source.commit("no mail", email = "", name = "No Mail")
                val captured = mutableListOf<MirrorCommit>()
                val capturing = object : GitMirrorIndexer(commitIndexer, entityManager) {
                    override fun index(id: Long, owner: String, fence: Long, commits: List<MirrorCommit>, refNames: List<String>, positions: Map<String, String>, deletions: Collection<String>) {
                        captured += commits
                        super.index(id, owner, fence, commits, refNames, positions, deletions)
                    }
                }
                sync(localSeam(engineOf(5, capturing)))
                captured.size shouldBe 2
                captured[0].let {
                    it.id shouldBe d.name
                    it.message shouldBe "third\n\nbody"
                    it.authorName shouldBe "Dev"
                    it.authorEmail shouldBe "late@example.test"
                    it.authorKey shouldBe "late@example.test"
                    it.timestamp shouldBe d.authorIdent.whenAsInstant
                }
                captured[1].let {
                    it.id shouldBe e.name
                    it.authorName shouldBe "No Mail"
                    it.authorEmail shouldBe null
                    it.authorKey shouldBe null
                }
            }

            it("resumes after a crash between chunks without duplicate references or notifications") {
                val engine = localSeam(engineOf(2))
                val base = source.commit("base #1")
                sync(engine)
                val added = (1..5).map { source.commit("next $it #1") }
                var calls = 0
                val crashing = object : GitMirrorIndexer(commitIndexer, entityManager) {
                    override fun index(id: Long, owner: String, fence: Long, commits: List<MirrorCommit>, refNames: List<String>, positions: Map<String, String>, deletions: Collection<String>) {
                        super.index(id, owner, fence, commits, refNames, positions, deletions)
                        if (++calls == 1) throw IllegalStateException("crash after the first chunk")
                    }
                }
                shouldThrow<IllegalStateException> { sync(localSeam(engineOf(2, crashing))) }
                referenced() shouldContainExactlyInAnyOrder ids(base, added[0], added[1])
                rows()["refs/heads/main"] shouldBe added[1].name
                notices().size shouldBe 1

                sync(engine)
                referenced() shouldContainExactlyInAnyOrder ids(base, *added.toTypedArray())
                rows()["refs/heads/main"] shouldBe added[4].name
                notices().size shouldBe 3
                verify(exactly = 3) { webhooks.sendMirrorPushWebhook(any(), any()) }
            }

            it("resumes a merge whose side branch fell into an earlier chunk, without duplicates") {
                val engine = localSeam(engineOf(3))
                val a = source.commit("a #1")
                source.checkout("side", create = true, from = a)
                val s = source.commit("side #1")
                source.checkout("main")
                val b = source.commit("b #1")
                source.checkout("side")
                // Parents are [side, b], so the side commit sorts before b and falls before a chunk boundary of 3.
                val mergeId = source.git.merge().include(b).setFastForward(MergeCommand.FastForwardMode.NO_FF).setCommit(true).setMessage("merge #1").call().newHead
                val merge = source.git.repository.parseCommit(mergeId)
                source.ref("refs/heads/merged", merge)
                source.checkout("merged")
                source.git.branchDelete().setBranchNames("side").setForce(true).call()
                source.git.branchDelete().setBranchNames("main").setForce(true).call()
                var calls = 0
                val crashing = object : GitMirrorIndexer(commitIndexer, entityManager) {
                    override fun index(id: Long, owner: String, fence: Long, commits: List<MirrorCommit>, refNames: List<String>, positions: Map<String, String>, deletions: Collection<String>) {
                        super.index(id, owner, fence, commits, refNames, positions, deletions)
                        if (++calls == 1) throw IllegalStateException("crash after the first chunk")
                    }
                }
                shouldThrow<IllegalStateException> { sync(localSeam(engineOf(3, crashing))) }
                val afterCrash = referenced()
                afterCrash.size shouldBe 3
                rows().keys.any { it.startsWith("mirror-pending/") } shouldBe true
                sync(engine)
                referenced() shouldContainExactlyInAnyOrder ids(a, s, b, merge)
                rows().keys.none { it.startsWith("mirror-pending/") } shouldBe true
                rows()["refs/heads/merged"] shouldBe merge.name
            }

            it("follows a force-push and a deleted branch, keeping their old positions so history is never indexed twice") {
                val engine = localSeam(engineOf(5))
                val c1 = source.commit("c1 #1")
                val c2 = source.commit("c2 #1")
                val c3 = source.commit("c3 #1")
                source.ref("refs/heads/doomed", c2)
                sync(engine)
                referenced().size shouldBe 3

                source.resetTo(c1)
                val n1 = source.commit("rewritten #1")
                sync(engine)
                referenced() shouldContainExactlyInAnyOrder ids(c1, c2, c3, n1)
                rows()["refs/heads/main"] shouldBe n1.name
                notices().size shouldBe 1

                source.resetTo(c1)
                sync(engine)
                referenced().size shouldBe 4
                rows()["refs/heads/main"] shouldBe c1.name
                retired() shouldBe setOf(c3.name, n1.name)
                notices().size shouldBe 1

                source.git.branchDelete().setBranchNames("doomed").setForce(true).call()
                sync(engine)
                rows().keys shouldBe setOf("refs/heads/main")
                retired() shouldBe setOf(c2.name, c3.name, n1.name)
                referenced().size shouldBe 4
                notices().size shouldBe 1

                // Refs reaching rewritten or deleted history later add no second reference.
                source.ref("refs/heads/revived", c3)
                source.ref("refs/heads/revived-rewrite", n1)
                sync(engine)
                referenced().size shouldBe 4
                notices().size shouldBe 1
                val local = Git.open(local)
                try {
                    local.repository.exactRef("refs/heads/doomed") shouldBe null
                } finally { local.close() }
            }

            it("follows deletion of the branch that the source HEAD pointed at") {
                val engine = localSeam(engineOf(5))
                val c1 = source.commit("c1 #1")
                source.checkout("other", create = true, from = c1)
                val o1 = source.commit("o1 #1")
                source.checkout("main")
                sync(engine)
                source.checkout("other")
                source.git.branchDelete().setBranchNames("main").setForce(true).call()
                sync(engine)
                rows() shouldBe mapOf("refs/heads/other" to o1.name)
            }

            it("adds no references for a new branch or tag at an already indexed commit") {
                val engine = localSeam(engineOf(5))
                val c1 = source.commit("c1 #1")
                val c2 = source.commit("c2 #1")
                val c3 = source.commit("c3 #1")
                sync(engine)
                source.ref("refs/heads/from-ancestor", c2)
                source.ref("refs/heads/from-tip", c3)
                source.git.tag().setName("annotated-old").setAnnotated(true).setMessage("old").setSigned(false).setObjectId(c1).call()
                sync(engine)
                referenced().size shouldBe 3
                notices().size shouldBe 0
                rows() shouldBe mapOf(
                    "refs/heads/main" to c3.name, "refs/heads/from-ancestor" to c2.name, "refs/heads/from-tip" to c3.name,
                    "refs/tags/annotated-old" to c1.name
                )
            }

            it("indexes a commit that only an annotated tag reaches, at the peeled commit, and skips ref names over 255 characters") {
                val engine = localSeam(engineOf(5))
                val c1 = source.commit("c1 #1")
                sync(engine)
                source.checkout("work", create = true)
                val onlyTagged = source.commit("tagged #1")
                val hidden = source.commit("hidden by long name #1")
                source.checkout("main")
                source.git.branchDelete().setBranchNames("work").setForce(true).call()
                source.git.tag().setName("release").setAnnotated(true).setMessage("rel").setSigned(false).setObjectId(onlyTagged).call()
                val longName = "refs/heads/" + "a".repeat(250)
                source.ref(longName, hidden)
                sync(engine)
                referenced() shouldContainExactlyInAnyOrder ids(c1, onlyTagged)
                rows() shouldBe mapOf("refs/heads/main" to c1.name, "refs/tags/release" to onlyTagged.name)
                notices().size shouldBe 1
            }

            it("rejects a non-empty directory that is not a repository and never touches it") {
                val engine = localSeam(engineOf(5))
                source.commit("c1 #1")
                local.resolve("stranger.txt").writeText("not a repository")
                val failure = shouldThrow<MirrorFailure> { engine.sync(context()) }
                failure.code shouldBe "TARGET_INCOMPLETE"
                failure.attention shouldBe true
                local.list()!!.toList() shouldBe listOf("stranger.txt")
                referenced().size shouldBe 0
            }

            it("does not recreate a lost local repository once positions exist") {
                val engine = localSeam(engineOf(5))
                source.commit("c1 #1")
                sync(engine)
                local.deleteRecursively()
                local.mkdirs()
                val failure = shouldThrow<MirrorFailure> { engine.sync(context()) }
                failure.code shouldBe "TARGET_INCOMPLETE"
                failure.attention shouldBe true
                local.list()!!.size shouldBe 0
            }

            it("completes against an empty source and notifies its first later commit") {
                val engine = localSeam(engineOf(5))
                sync(engine)
                store.snapshot(mirror.id!!).initialImportDone shouldBe true
                rows().size shouldBe 0
                source.commit("first #1")
                sync(engine)
                referenced().size shouldBe 1
                notices().size shouldBe 1
            }

            it("reads author keys locally from the mirrored history") {
                val engine = localSeam(engineOf(5))
                val c1 = source.commit("c1", email = "b@example.test")
                val c2 = source.commit("c2", email = "a@example.test")
                source.commit("c3", email = "", name = "nobody")
                source.commit("c4", email = "b@example.test")
                source.ref("refs/tags/annotated", c1)
                sync(engine)
                val remote = source.dir
                source.git.close()
                remote.deleteRecursively() // Proves no network or source access.
                engine.authorKeyOf(mirror, local, c1.name) shouldBe "b@example.test"
                engine.authorKeyOf(mirror, local, c2.name) shouldBe "a@example.test"
                engine.authorKeyOf(mirror, local, "0".repeat(40)) shouldBe null
                engine.authorKeyOf(mirror, local, "not-a-hash") shouldBe null
                engine.authorKeys(mirror, local, 10) shouldBe listOf("a@example.test", "b@example.test")
                engine.authorKeys(mirror, local, 1).size shouldBe 1
                source = Source(remote.also { it.mkdirs() })
            }

            describe("real transport path") {
                val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 3128))
                val normalized = "https://git.example.test:443/owner/repo.git"
                fun serve(status: Int, header: Pair<String, String>? = null): HttpServer {
                    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                    server.createContext("/") { exchange ->
                        header?.let { exchange.responseHeaders.add(it.first, it.second) }
                        exchange.sendResponseHeaders(status, -1)
                        exchange.close()
                    }
                    server.start()
                    return server
                }
                fun wired(server: HttpServer, proxies: MutableList<Proxy>): GitMirrorEngine {
                    every { policy.normalize(any()) } returns normalized
                    every { policy.proxy() } returns proxy
                    val engine = engineOf(5)
                    engine.connectionOpener = { url, used ->
                        proxies += used
                        URL("http://127.0.0.1:${server.address.port}${url.path}?${url.query}").openConnection(Proxy.NO_PROXY) as HttpURLConnection
                    }
                    return engine
                }

                it("surfaces a source redirect as SOURCE_REDIRECT, through the policy proxy and host check, with no global factory change") {
                    val server = serve(302, "Location" to "https://elsewhere.example.test/")
                    try {
                        val proxies = mutableListOf<Proxy>()
                        val jgit = HttpTransport.getConnectionFactory()
                        val failure = shouldThrow<MirrorFailure> { wired(server, proxies).sync(context()) }
                        failure.code shouldBe "SOURCE_REDIRECT"
                        proxies.isNotEmpty() shouldBe true
                        proxies.all { it === proxy } shouldBe true
                        verify(atLeast = 1) { policy.checkHost("git.example.test") }
                        (HttpTransport.getConnectionFactory() === jgit) shouldBe true
                    } finally { server.stop(0) }
                }

                it("maps a 401 to SOURCE_AUTH_REJECTED and clears the credential password") {
                    val server = serve(401, "WWW-Authenticate" to "Basic realm=\"git\"")
                    try {
                        transaction.executeWithoutResult { mirrors.findById(mirror.id!!).orElseThrow().credentialRef = "reader" }
                        val password = "s3cret".toCharArray()
                        every { policy.credential("reader") } returns ("reader-user" to password)
                        val failure = shouldThrow<MirrorFailure> { wired(server, mutableListOf()).sync(context()) }
                        failure.code shouldBe "SOURCE_AUTH_REJECTED"
                        password.all { it == '\u0000' } shouldBe true
                    } finally { server.stop(0) }
                }

                it("requires activation before any policy or network use") {
                    val engine = GitMirrorEngine(policy, indexer, refRows, RepositoryMirrorProperties())
                    shouldThrow<MirrorFailure> { engine.sync(context()) }.code shouldBe "FEATURE_DISABLED"
                    verify(exactly = 0) { policy.normalize(any()) }
                    verify(exactly = 0) { policy.checkHost(any()) }
                }
            }
        }
    }
}
