package com.github.yonaprojects.yona.domain.project

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.domain.board.Posting
import com.github.yonaprojects.yona.domain.board.PostingRepository
import com.github.yonaprojects.yona.domain.issue.Issue
import com.github.yonaprojects.yona.domain.issue.IssueRepository
import com.github.yonaprojects.yona.domain.pullrequest.PullRequestService
import com.github.yonaprojects.yona.domain.pullrequest.PullRequest
import com.github.yonaprojects.yona.domain.pullrequest.PullRequestRepository
import com.github.yonaprojects.yona.domain.vcs.RepositoryAccess
import com.github.yonaprojects.yona.config.RepositoryAccessFilter
import org.springframework.web.context.WebApplicationContext
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import com.github.yonaprojects.yona.domain.vcs.BareCommit
import com.github.yonaprojects.yona.domain.vcs.PendingRepositoryReset
import com.github.yonaprojects.yona.domain.vcs.PlayRepository
import com.github.yonaprojects.yona.domain.vcs.RepositoryArchive
import com.github.yonaprojects.yona.domain.vcs.RepositoryService
import com.github.yonaprojects.yona.domain.vcs.nextVcsInCycle
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.spyk
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@TestConfiguration
class ArchiveFailureTestConfiguration {
    @Bean
    @Primary
    fun resetRepositories(@Qualifier("repositoryService") original: RepositoryService): RepositoryService = spyk(original)

    @Bean
    @Primary
    fun resetArchives(@Qualifier("repositoryArchive") original: RepositoryArchive): RepositoryArchive = spyk(original)
}

@Import(ArchiveFailureTestConfiguration::class)
class ProjectChangeVcsArchiveIntegrationSpec @Autowired constructor(
    private val projectService: ProjectService,
    private val projectRepository: ProjectRepository,
    private val userRepository: UserRepository,
    private val issueRepository: IssueRepository,
    private val postingRepository: PostingRepository,
    private val repositoryService: RepositoryService,
    private val repositoryArchive: RepositoryArchive,
    private val entityManager: EntityManager,
    private val transactionManager: PlatformTransactionManager,
    private val pullRequestService: PullRequestService,
    private val pullRequestRepository: PullRequestRepository,
    private val context: WebApplicationContext,
    private val gitService: GitService
) : AbstractIntegrationTest() {
    override fun extensions() = listOf(SpringExtension)

    companion object {
        private val root = Files.createTempDirectory("yona-reset-archive-it-")

        @JvmStatic
        @DynamicPropertySource
        fun storage(registry: DynamicPropertyRegistry) {
            listOf("git", "svn", "hg", "lfs", "upload").forEach { vcs ->
                registry.add("yona.$vcs.base-dir") { root.resolve(vcs).toString() }
            }
            registry.add("yona.repository-archive.base-dir") { root.resolve("archive").toString() }
            registry.add("yona.repository-archive.single-serving-process") { "true" }
        }
    }

    private data class Fixture(
        val project: Project, val owner: User, val fork: Project,
        val source: Path, val target: Path, val revision: String, val references: String,
        val issueId: Long, val postingId: Long, val marker: String
    )

    private val operations = ConcurrentHashMap<Long, PendingRepositoryReset>()
    private val projects = mutableListOf<Long>()
    private val users = mutableListOf<Long>()
    private val transaction get() = TransactionTemplate(transactionManager)

    private fun command(directory: Path, vararg arguments: String): String {
        val process = ProcessBuilder(*arguments).directory(directory.toFile()).redirectErrorStream(true)
            .apply {
                environment()["HGPLAIN"] = "1"
                environment()["HGRCPATH"] = ""
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
            }.start()
        val completed = process.waitFor(60, TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        val output = process.inputStream.bufferedReader().readText()
        withClue(output) { completed shouldBe true; process.exitValue() shouldBe 0 }
        return output.trim()
    }

    private fun commit(project: Project, owner: User, marker: String) {
        val directory = repositoryService.getRepository(project).getDirectory().toPath()
        when (project.vcs) {
            "GIT" -> BareCommit(project, owner, root.resolve("git").toString())
                .commitTextFile("archive-marker.txt", marker, "marker before reset")
            "SUBVERSION" -> {
                val imports = Files.createTempDirectory(root, "svn-import-")
                imports.resolve("archive-marker.txt").toFile().writeText(marker)
                command(root, "svn", "--non-interactive", "--config-dir", root.resolve("svn-config").toString(),
                    "import", imports.toString(), directory.toUri().toString(), "-m", "marker before reset")
            }
            "MERCURIAL" -> {
                directory.resolve("archive-marker.txt").toFile().writeText(marker)
                command(directory, "hg", "add", "archive-marker.txt")
                command(directory, "hg", "commit", "-u", "reset-test", "-m", "marker before reset")
                command(directory, "hg", "bookmark", "reset-marker")
            }
        }
    }

    private fun revision(vcs: String, directory: Path): String = when (vcs) {
        "GIT" -> command(root, "git", "--git-dir=$directory", "rev-parse", "HEAD")
        "SUBVERSION" -> command(root, "svnlook", "youngest", directory.toString())
        else -> command(root, "hg", "-R", directory.toString(), "log", "-r", "tip", "-T", "{node}")
    }

    private fun references(vcs: String, directory: Path): String = when (vcs) {
        "GIT" -> command(root, "git", "--git-dir=$directory", "show-ref")
        "SUBVERSION" -> revision(vcs, directory)
        else -> command(root, "hg", "-R", directory.toString(), "bookmarks", "-T", "{bookmark}:{node}\\n")
    }

    private fun marker(vcs: String, directory: Path): String = when (vcs) {
        "GIT" -> command(root, "git", "--git-dir=$directory", "show", "HEAD:archive-marker.txt")
        "SUBVERSION" -> command(root, "svnlook", "cat", directory.toString(), "archive-marker.txt")
        else -> command(directory, "hg", "cat", "-r", "tip", "archive-marker.txt")
    }

    private fun fixture(vcs: String): Fixture {
        val owner = userRepository.save(User(loginId = "archive-${UUID.randomUUID().toString().take(8)}", name = "Archive test"))
        users.add(owner.id!!)
        val project = projectService.createProject(Project(owner = owner.loginId, name = "source", vcs = vcs), owner)
        projects.add(project.id!!)
        val fork = projectService.createProject(Project(owner = owner.loginId, name = "fork", vcs = vcs), owner)
        projects.add(fork.id!!)
        fork.originalProject = project
        projectRepository.save(fork)
        repositoryService.getRepository(fork).getDirectory().resolve("fork-sentinel").writeText("fork stays")
        val marker = "archive-marker-${project.id}-$vcs"
        commit(project, owner, marker)
        val issue = issueRepository.save(Issue(project = project, number = 1L, title = "keep issue", body = marker))
        val posting = postingRepository.save(Posting(project = project, number = 1L, title = "keep board", body = marker))
        val source = repositoryService.getRepository(project).getDirectory().toPath()
        val target = repositoryService.getRepository(project, nextVcsInCycle(vcs)).getDirectory().toPath()
        return Fixture(project, owner, fork, source, target, revision(vcs, source), references(vcs, source), issue.id!!, posting.id!!, marker)
    }

    private fun reset(fixture: Fixture): Project = projectService.changeVCS(
        fixture.project.id!!, VcsResetConfirmation(fixture.project.id, fixture.project.name, fixture.project.vcs, true)
    )

    private fun assertDatabase(fixture: Fixture, vcs: String, disconnected: Boolean) {
        transaction.executeWithoutResult {
            val current = projectRepository.findById(fixture.project.id!!).orElseThrow()
            current.id shouldBe fixture.project.id
            current.name shouldBe fixture.project.name
            current.vcs shouldBe vcs
            val originalId = projectRepository.findById(fixture.fork.id!!).orElseThrow().originalProject?.id
            originalId shouldBe if (disconnected) null else fixture.project.id
            issueRepository.findById(fixture.issueId).orElseThrow().body shouldBe fixture.marker
            postingRepository.findById(fixture.postingId).orElseThrow().body shouldBe fixture.marker
        }
        repositoryService.getRepository(fixture.fork).getDirectory().resolve("fork-sentinel").readText() shouldBe "fork stays"
    }

    private fun assertOriginal(fixture: Fixture) {
        val vcs = fixture.project.vcs!!
        assertDatabase(fixture, vcs, false)
        revision(vcs, fixture.source) shouldBe fixture.revision
        references(vcs, fixture.source) shouldBe fixture.references
        marker(vcs, fixture.source) shouldBe fixture.marker
        Files.exists(fixture.target) shouldBe false
    }

    private fun interceptCreation(fixture: Fixture, action: (PlayRepository) -> Unit) {
        every {
            repositoryService.getRepository(match { it.id == fixture.project.id }, nextVcsInCycle(fixture.project.vcs))
        } answers {
            val actual = callOriginal() as PlayRepository
            object : PlayRepository by actual {
                override fun create() = action(actual)
            }
        }
    }

    init {
        beforeTest {
            clearMocks(repositoryService, repositoryArchive)
            operations.clear()
            every { repositoryArchive.begin(any(), any(), any(), any()) } answers {
                (callOriginal() as PendingRepositoryReset).also { operations[firstArg<Long>()] = it }
            }
        }
        afterSpec {
            clearMocks(repositoryService, repositoryArchive)
            for (id in projects.asReversed()) if (projectRepository.existsById(id)) projectService.deleteProject(id)
            for (id in users) userRepository.deleteById(id)
            root.toFile().deleteRecursively()
        }

        describe("reset archive with real database transactions and committed Git/SVN/Hg data") {
            it("keeps retained repositories outside normal import sources without changing an existing destination") {
                val fixture = fixture("GIT")
                reset(fixture)
                val archived = operations.getValue(fixture.project.id!!).archiveDirectory
                val destination = gitService.createRepository(fixture.owner.loginId, "import-destination")
                val sentinel = destination.resolve("sentinel").apply { writeText("keep destination") }
                for (source in listOf(archived.toString(), archived.toUri().toString())) {
                    shouldThrow<com.github.yonaprojects.yona.domain.vcs.RepositoryUnavailableException> {
                        gitService.cloneRepository(source, fixture.owner.loginId, "import-destination", null, null)
                    }
                    sentinel.readText() shouldBe "keep destination"
                    marker("GIT", archived) shouldBe fixture.marker
                }
            }

            it("keeps archived Mercurial data unchanged when an existing native fork is updated") {
                val fixture = fixture("MERCURIAL")
                val fork = projectService.forkProject(
                    fixture.project.id!!, fixture.owner.id!!, fixture.owner.loginId, "native-fork"
                )
                projects.add(fork.id!!)
                val forkRepository = repositoryService.getRepository(fork)
                Files.isSameFile(
                    fixture.source.resolve("archive-marker.txt"),
                    forkRepository.getDirectory().toPath().resolve("archive-marker.txt")
                ) shouldBe true
                reset(fixture)
                val archived = operations.getValue(fixture.project.id!!).archiveDirectory
                fun snapshot(): Map<String, String> = Files.walk(archived).use { paths ->
                    paths.filter { Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }
                        .toList().associate {
                            archived.relativize(it).toString() to java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(it))
                        }
                }
                val before = snapshot()
                RepositoryAccess.read(fork.id!!) {
                    forkRepository.commitTextFile(
                        "reset-marker", null, "archive-marker.txt", "fork after reset",
                        "update fork", "fork-author", "fork@example.invalid"
                    )
                }
                snapshot() shouldBe before
                marker("MERCURIAL", archived) shouldBe fixture.marker
                marker("MERCURIAL", forkRepository.getDirectory().toPath()) shouldBe "fork after reset"
            }

            for (vcs in listOf("GIT", "SUBVERSION", "MERCURIAL")) {
                it("$vcs rename remains within the configured repository root") {
                    val fixture = fixture(vcs)
                    shouldThrow<com.github.yonaprojects.yona.domain.vcs.RepositoryUnavailableException> {
                        projectService.updateProject(
                            fixture.project.id!!,
                            UpdateProjectParam(name = "../../archive/rename-${fixture.project.id}", overview = "unchanged")
                        )
                    }
                    projectRepository.findById(fixture.project.id!!).orElseThrow().name shouldBe fixture.project.name
                    marker(vcs, fixture.source) shouldBe fixture.marker
                    val suffix = if (vcs == "GIT") ".git" else ""
                    Files.exists(root.resolve("archive/rename-${fixture.project.id}$suffix")) shouldBe false
                }
            }

            for (vcs in listOf("GIT", "SUBVERSION", "MERCURIAL")) {
                it("$vcs success archives all original history and preserves collaboration data") {
                    val fixture = fixture(vcs)
                    val changed = reset(fixture)
                    val archived = operations.getValue(fixture.project.id!!).archiveDirectory

                    Files.exists(fixture.source) shouldBe false
                    revision(vcs, archived) shouldBe fixture.revision
                    references(vcs, archived) shouldBe fixture.references
                    marker(vcs, archived) shouldBe fixture.marker
                    repositoryService.getRepository(changed).isEmpty() shouldBe true
                    assertDatabase(fixture, nextVcsInCycle(vcs), true)
                }

                for (partial in listOf(false, true)) {
                    it("$vcs restores actual history when creation fails ${if (partial) "after partial target files" else "before writing target contents"}") {
                        val fixture = fixture(vcs)
                        interceptCreation(fixture) { actual ->
                            if (partial) actual.create()
                            throw IOException("injected create failure")
                        }
                        val failure = shouldThrow<RepositoryResetFailure> { reset(fixture) }
                        failure.message!!.contains(root.toString()) shouldBe false
                        assertOriginal(fixture)
                        Files.exists(operations.getValue(fixture.project.id!!).archiveDirectory) shouldBe false
                        Files.isDirectory(operations.getValue(fixture.project.id!!).failedTargetDirectory!!) shouldBe true
                    }
                }

                for (flush in listOf(false, true)) {
                    it("$vcs restores original files and fork metadata after real DB ${if (flush) "flush" else "commit-time flush"} failure") {
                        val fixture = fixture(vcs)
                        var completion = TransactionSynchronization.STATUS_UNKNOWN
                        interceptCreation(fixture) { actual ->
                            actual.create()
                            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                                override fun afterCompletion(status: Int) { completion = status }
                            })
                            // The actual schema limits Project.name to 255 characters. Fail through JPA/DB, not a mocked exception.
                            entityManager.find(Project::class.java, fixture.project.id).name = "x".repeat(300)
                            if (flush) entityManager.flush()
                        }

                        shouldThrow<RepositoryResetFailure> { reset(fixture) }
                        completion shouldBe TransactionSynchronization.STATUS_ROLLED_BACK
                        assertOriginal(fixture)
                    }
                }
            }

            it("target collision preserves somebody else's files and the original repository") {
                val fixture = fixture("GIT")
                Files.createDirectories(fixture.target)
                val sentinel = fixture.target.resolve("not-ours")
                sentinel.toFile().writeText("do not delete")

                shouldThrow<RepositoryResetFailure> { reset(fixture) }

                sentinel.toFile().readText() shouldBe "do not delete"
                marker("GIT", fixture.source) shouldBe fixture.marker
                assertDatabase(fixture, "GIT", false)
                operations[fixture.project.id] shouldBe null
            }

            it("missing source does not create a target or disconnect forks") {
                val fixture = fixture("GIT")
                fixture.source.toFile().deleteRecursively() shouldBe true

                shouldThrow<RepositoryResetFailure> { reset(fixture) }

                Files.exists(fixture.target) shouldBe false
                assertDatabase(fixture, "GIT", false)
            }

            it("restore collision retains the archive and foreign source contents for operator recovery") {
                val fixture = fixture("GIT")
                interceptCreation(fixture) { actual ->
                    actual.create()
                    Files.createDirectory(fixture.source)
                    fixture.source.resolve("not-ours").toFile().writeText("do not delete")
                    throw IOException("create failed with restore collision")
                }

                val failure = shouldThrow<RepositoryResetFailure> { reset(fixture) }
                failure.cause!!.suppressed.size shouldBe 1
                val archived = operations.getValue(fixture.project.id!!).archiveDirectory
                marker("GIT", archived) shouldBe fixture.marker
                fixture.source.resolve("not-ours").toFile().readText() shouldBe "do not delete"
                assertDatabase(fixture, "GIT", false)

                // Only this fixture owns the collision sentinel. Exercise the documented operator restore itself.
                fixture.source.toFile().deleteRecursively() shouldBe true
                Files.move(archived, fixture.source, ATOMIC_MOVE)
                assertOriginal(fixture)
            }

            it("failed target quarantine movement keeps the original archive for operator recovery")
                .config(enabled = System.getProperty("user.name") != "root") {
                    val fixture = fixture("GIT")
                    interceptCreation(fixture) { actual ->
                        actual.create()
                        Files.setPosixFilePermissions(fixture.target.parent, PosixFilePermissions.fromString("r-x------"))
                        throw IOException("partial create with non-writable target parent")
                    }
                    try {
                        val failure = shouldThrow<RepositoryResetFailure> { reset(fixture) }
                        failure.cause!!.suppressed.size shouldBe 1
                        marker("GIT", operations.getValue(fixture.project.id!!).archiveDirectory) shouldBe fixture.marker
                        Files.exists(fixture.source) shouldBe false
                        assertDatabase(fixture, "GIT", false)
                    } finally {
                        Files.setPosixFilePermissions(fixture.target.parent, PosixFilePermissions.fromString("rwx------"))
                    }
                    operations.getValue(fixture.project.id!!).rollback()
                    assertOriginal(fixture)
                }

            it("an afterCommit failure preserves the committed target and archived original") {
                val fixture = fixture("GIT")
                interceptCreation(fixture) { actual ->
                    actual.create()
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun afterCommit() { throw IllegalStateException("injected post-commit failure") }
                    })
                }

                shouldThrow<RepositoryResetFailure> { reset(fixture) }

                assertDatabase(fixture, "SUBVERSION", true)
                Files.exists(fixture.target) shouldBe true
                Files.exists(fixture.source) shouldBe false
                marker("GIT", operations.getValue(fixture.project.id!!).archiveDirectory) shouldBe fixture.marker
            }

            it("a real cross-project PR preview drains both repositories before resetting its source") {
                val source = fixture("GIT")
                val target = fixture("GIT")
                val opened = CountDownLatch(1)
                val release = CountDownLatch(1)
                every { repositoryService.getRepository(match { it.id == source.project.id }, null) } answers {
                    opened.countDown()
                    check(release.await(60, TimeUnit.SECONDS)) { "PR fixture did not release its repository session" }
                    callOriginal()
                }
                val preview = CompletableFuture.supplyAsync {
                    pullRequestService.previewMerge(source.project, target.project, "main", "main")
                }
                try {
                    opened.await(60, TimeUnit.SECONDS) shouldBe true
                    val startedReset = CountDownLatch(1)
                    val resetting = CompletableFuture.supplyAsync {
                        startedReset.countDown()
                        reset(source)
                    }
                    startedReset.await(60, TimeUnit.SECONDS) shouldBe true
                    shouldThrow<java.util.concurrent.TimeoutException> { resetting.get(200, TimeUnit.MILLISECONDS) }
                    Files.exists(source.source) shouldBe true
                    operations[source.project.id] shouldBe null
                    release.countDown()
                    preview.get(60, TimeUnit.SECONDS)
                    resetting.get(60, TimeUnit.SECONDS)
                    marker("GIT", operations.getValue(source.project.id!!).archiveDirectory) shouldBe source.marker
                    assertDatabase(source, "SUBVERSION", true)
                } finally {
                    release.countDown()
                }
            }

            it("concurrent resets with one confirmation serialize and reject the stale second request") {
                val fixture = fixture("GIT")
                val start = CountDownLatch(1)
                val attempts = List(2) {
                    CompletableFuture.supplyAsync {
                        start.await()
                        runCatching { reset(fixture) }
                    }
                }
                start.countDown()
                val results = attempts.map { it.get(60, TimeUnit.SECONDS) }
                results.count { it.isSuccess } shouldBe 1
                results.count { it.exceptionOrNull() is VcsResetConflict } shouldBe 1
                assertDatabase(fixture, "SUBVERSION", true)
                marker("GIT", operations.getValue(fixture.project.id!!).archiveDirectory) shouldBe fixture.marker
            }

            it("three sequential resets preserve distinct Git, SVN and Hg archives without overwriting") {
                val fixture = fixture("GIT")
                val archives = mutableListOf<Path>()
                var current = fixture.project
                for (vcs in listOf("GIT", "SUBVERSION", "MERCURIAL")) {
                    if (vcs != "GIT") commit(current, fixture.owner, "cycle-$vcs")
                    current = projectService.changeVCS(current.id!!, VcsResetConfirmation(current.id, current.name, vcs, true))
                    val archived = operations.getValue(current.id!!).archiveDirectory
                    archives.add(archived)
                    marker(vcs, archived) shouldBe if (vcs == "GIT") fixture.marker else "cycle-$vcs"
                }
                archives.distinct().size shouldBe 3
                current.vcs shouldBe "GIT"
                repositoryService.getRepository(current).isEmpty() shouldBe true
            }

            for (route in listOf("mentionListAtCommitDiff", "mentionListAtPullRequest")) {
                it("$route returns the actual source commit author while draining a concurrent source reset") {
                    val source = fixture("GIT")
                    val target = fixture("GIT")
                    source.owner.email = "${source.owner.loginId}@example.com"
                    transaction.executeWithoutResult {
                        userRepository.findById(source.owner.id!!).orElseThrow().email = source.owner.email
                    }
                    val commitId = BareCommit(source.project, source.owner, root.resolve("git").toString())
                        .commitTextFile("mention.txt", "mention author", "mention fixture")!!.name
                    val pr = pullRequestRepository.save(PullRequest(
                        fromProject = source.project, toProject = target.project,
                        fromBranch = "main", toBranch = "main", contributor = target.owner, number = 1L
                    ))
                    val entered = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    every { repositoryService.getRepository(match { it.id == source.project.id }, null) } answers {
                        val actual = callOriginal() as PlayRepository
                        object : PlayRepository by actual {
                            override fun getCommit(id: String): com.github.yonaprojects.yona.domain.vcs.Commit? {
                                val commit = actual.getCommit(id)
                                entered.countDown()
                                check(release.await(60, TimeUnit.SECONDS))
                                return commit
                            }
                        }
                    }
                    val mvc = MockMvcBuilders.webAppContextSetup(context)
                        .apply<DefaultMockMvcBuilder>(springSecurity())
                        .addFilters<DefaultMockMvcBuilder>(context.getBean(RepositoryAccessFilter::class.java)).build()
                    val response = CompletableFuture.supplyAsync {
                        mvc.perform(get("/api/${target.project.owner}/${target.project.name}/$route")
                            .with(user(target.owner.loginId))
                            .param("commitId", commitId).param("pullRequestId", pr.id.toString())
                            .param("mentionType", "user")).andReturn().response
                    }
                    try {
                        entered.await(60, TimeUnit.SECONDS) shouldBe true
                        val resetting = CompletableFuture.supplyAsync { reset(source) }
                        shouldThrow<java.util.concurrent.TimeoutException> { resetting.get(200, TimeUnit.MILLISECONDS) }
                        Files.exists(source.source) shouldBe true
                        release.countDown()
                        val actual = response.get(60, TimeUnit.SECONDS)
                        actual.status shouldBe 200
                        actual.contentAsString.contains(source.owner.loginId) shouldBe true
                        resetting.get(60, TimeUnit.SECONDS)
                        marker("GIT", operations.getValue(source.project.id!!).archiveDirectory) shouldBe source.marker
                    } finally {
                        release.countDown()
                    }
                }
            }

            it("direct BareCommit writes wait for the reset gate before opening the repository") {
                val fixture = fixture("GIT")
                val writer = BareCommit(fixture.project, fixture.owner, root.resolve("git").toString())
                lateinit var committed: CompletableFuture<org.eclipse.jgit.lib.ObjectId?>
                RepositoryAccess.reset(fixture.project.id!!) {
                    committed = CompletableFuture.supplyAsync { writer.commitTextFile("direct.txt", "direct writer", "direct write") }
                    shouldThrow<java.util.concurrent.TimeoutException> { committed.get(200, TimeUnit.MILLISECONDS) }
                    revision("GIT", fixture.source) shouldBe fixture.revision
                }
                committed.get(60, TimeUnit.SECONDS) shouldNotBe null
                command(root, "git", "--git-dir=${fixture.source}", "show", "HEAD:direct.txt") shouldBe "direct writer"
            }

            for (operation in listOf("rename", "transfer", "delete")) {
                it("native $operation waits for an existing repository session and retains its normal behavior") {
                    val fixture = fixture("GIT")
                    val receiver = if (operation == "transfer") {
                        userRepository.save(User(loginId = "receiver-${UUID.randomUUID().toString().take(8)}", name = "Receiver"))
                            .also { users.add(it.id!!) }
                    } else null
                    val transfer = receiver?.let { projectService.requestNewTransfer(fixture.project.id!!, fixture.owner.id!!, it.loginId) }
                    lateinit var changed: CompletableFuture<Unit>
                    RepositoryAccess.read(fixture.project.id!!) {
                        revision("GIT", fixture.source) shouldBe fixture.revision
                        changed = CompletableFuture.supplyAsync {
                            when (operation) {
                                "rename" -> projectService.updateProject(fixture.project.id!!, UpdateProjectParam(name = "renamed", overview = "renamed"))
                                "transfer" -> projectService.acceptTransfer(transfer!!.id!!, transfer.confirmKey, receiver!!.id!!)
                                else -> projectService.deleteProject(fixture.project.id!!)
                            }
                            Unit
                        }
                        shouldThrow<java.util.concurrent.TimeoutException> { changed.get(200, TimeUnit.MILLISECONDS) }
                        Files.exists(fixture.source) shouldBe true
                    }
                    changed.get(60, TimeUnit.SECONDS)
                    operations[fixture.project.id] shouldBe null
                    if (operation == "delete") {
                        projectRepository.existsById(fixture.project.id!!) shouldBe false
                        Files.exists(fixture.source) shouldBe false
                    } else {
                        val current = projectRepository.findById(fixture.project.id!!).orElseThrow()
                        current.name shouldBe if (operation == "rename") "renamed" else fixture.project.name
                        current.owner shouldBe (receiver?.loginId ?: fixture.project.owner)
                        marker("GIT", repositoryService.getRepository(current).getDirectory().toPath()) shouldBe fixture.marker
                    }
                }
            }

            it("native fork waits for the reset gate and then clones actual committed history") {
                val fixture = fixture("GIT")
                lateinit var forked: CompletableFuture<Project>
                RepositoryAccess.reset(fixture.project.id!!) {
                    forked = CompletableFuture.supplyAsync {
                        projectService.forkProject(fixture.project.id!!, fixture.owner.id!!, fixture.owner.loginId, "gated-fork")
                    }
                    shouldThrow<java.util.concurrent.TimeoutException> { forked.get(200, TimeUnit.MILLISECONDS) }
                }
                val created = forked.get(60, TimeUnit.SECONDS)
                projects.add(created.id!!)
                marker("GIT", repositoryService.getRepository(created).getDirectory().toPath()) shouldBe fixture.marker
            }
        }
    }
}
