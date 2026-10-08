package com.github.yonaprojects.yona.domain.vcs

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.domain.enumeration.EventType
import com.github.yonaprojects.yona.domain.issue.Issue
import com.github.yonaprojects.yona.domain.issue.IssueEvent
import com.github.yonaprojects.yona.domain.issue.IssueEventRepository
import com.github.yonaprojects.yona.domain.issue.IssueRepository
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.project.RepositoryMode
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import com.github.yonaprojects.yona.domain.user.UserState
import com.github.yonaprojects.yona.domain.user.YonaUserDetails
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import org.tmatesoft.svn.core.SVNPropertyValue
import org.tmatesoft.svn.core.SVNRevisionProperty
import org.tmatesoft.svn.core.internal.io.fs.FSRepositoryFactory
import org.tmatesoft.svn.core.io.SVNRepositoryFactory
import java.nio.file.Files
import java.util.UUID

class RepositoryMirrorAuthorServiceSpec @Autowired constructor(
    private val wac: WebApplicationContext,
    private val projects: ProjectRepository,
    private val users: UserRepository,
    private val issues: IssueRepository,
    private val references: IssueEventRepository,
    private val mirrors: RepositoryMirrorRepository,
    private val mappings: RepositoryMirrorAuthorRepository,
    private val store: RepositoryMirrorStore,
    private val repositoryService: RepositoryService,
    private val entityManager: EntityManager,
    transactionManager: PlatformTransactionManager
) : AbstractIntegrationTest() {
    private val mvc by lazy {
        MockMvcBuilders.webAppContextSetup(wac)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity()).build()
    }
    private val transaction = TransactionTemplate(transactionManager)
    private val base = Files.createTempDirectory("mirror-author-it-").toRealPath()
    private val namespaceGuard = RepositoryNamespaceGuard(base.resolve("_data").toString(), base.toString())
    private val storage = RepositoryMirrorStorage(base.toString(), base.resolve("_git").toString(), base.resolve("_hg").toString(), namespaceGuard)
    private val service = RepositoryMirrorAuthorService(mappings, users, storage, store, entityManager, transactionManager)

    private lateinit var admin: User
    private lateinit var alice: User
    private lateinit var bob: User
    private lateinit var project: Project
    private lateinit var issue: Issue
    private lateinit var mirror: RepositoryMirror

    private fun auth(account: User) = user(YonaUserDetails(
        id = account.id!!, loginId = account.loginId, passwordVal = "h", passwordSalt = "s",
        authoritiesVal = AuthorityUtils.createAuthorityList(if (account.isSiteManager) "ROLE_SITE_ADMIN" else "ROLE_ACTIVE")
    ))

    private fun reference(revision: Long, sender: User? = null) = references.save(IssueEvent(
        issue = issue, senderLoginId = sender?.loginId, senderEmail = sender?.email,
        newValue = revision.toString(), eventType = EventType.ISSUE_REFERRED_FROM_COMMIT
    ))

    private fun senders() = references.findByIssueOrderByCreatedAsc(issue).sortedBy { it.newValue }.map { it.newValue to it.senderLoginId }

    init {
        FSRepositoryFactory.setup()

        beforeEach {
            val suffix = UUID.randomUUID().toString().take(8)
            admin = users.save(User(loginId = "author-admin-$suffix", name = "Admin", state = UserState.SITE_ADMIN, email = "admin-$suffix@example.test"))
            alice = users.save(User(loginId = "alice-$suffix", name = "Alice", email = "alice-$suffix@example.test"))
            bob = users.save(User(loginId = "bob-$suffix", name = "Bob", email = "bob-$suffix@example.test"))
            project = projects.save(Project(name = "authors-$suffix", owner = admin.loginId, vcs = "SUBVERSION", repositoryMode = RepositoryMode.MIRROR))
            issue = issues.save(Issue(project = project, number = 1, title = "Referenced"))
            // r1 alice, r2 "bob" (same name as a Yona account prefix, never auto-matched), r3 alice; r4 is unverified.
            val directory = storage.reserve(project, 1)
            val local = SVNRepositoryFactory.create(SVNRepositoryFactory.createLocalRepository(directory, true, false))
            listOf("svn-alice", "svn-bob", "svn-alice", "svn-late").forEachIndexed { index, author ->
                val editor = local.getCommitEditor("revision ${index + 1} #1", null)
                editor.openRoot(-1); editor.addDir("d$index", null, -1); editor.closeDir(); editor.closeDir()
                val revision = editor.closeEdit().newRevision
                local.setRevisionPropertyValue(revision, SVNRevisionProperty.AUTHOR, SVNPropertyValue.create(author))
            }
            local.closeSession()
            mirror = mirrors.save(RepositoryMirror(
                project = project, sourceUrl = "https://svn.example.test/repository",
                sourceRepositoryUuid = "source-uuid", localRepositoryUuid = "local-uuid",
                initialImportTargetRevision = 3, lastVerifiedRevision = 3, lastIndexedRevision = 3,
                localYoungestRevision = 4, sourceYoungestRevision = 4, status = RepositoryMirrorStatus.INCREMENTAL
            ))
        }

        afterEach {
            transaction.executeWithoutResult {
                references.deleteAll(references.findByIssueOrderByCreatedAsc(issue))
                mappings.deleteAll(mappings.findAllByMirrorIdOrderBySourceAuthorAsc(mirror.id!!))
                mirrors.deleteById(mirror.id!!)
                issues.deleteById(issue.id!!)
                projects.deleteById(project.id!!)
                listOf(admin, alice, bob).forEach { users.deleteById(it.id!!) }
            }
        }

        afterSpec { base.toFile().deleteRecursively() }

        describe("source author mappings") {
            it("lists authors of verified revisions only and nothing is mapped by name") {
                val view = service.view(admin.loginId, mirror.id!!)
                view.unmappedAuthors shouldBe listOf("svn-alice", "svn-bob")
                view.mappings shouldBe emptyList()
            }

            it("credits existing unattributed references of that author only and keeps credited ones") {
                reference(1); reference(2); reference(3, sender = bob); reference(4)
                service.add(admin.loginId, mirror.id!!, "svn-alice", alice.loginId) shouldBe 1
                senders() shouldBe listOf("1" to alice.loginId, "2" to null, "3" to bob.loginId, "4" to null)
                references.findByIssueOrderByCreatedAsc(issue).single { it.newValue == "1" }.senderEmail shouldBe alice.email
                service.view(admin.loginId, mirror.id!!).unmappedAuthors shouldBe listOf("svn-bob")
            }

            it("removing a mapping clears only references credited through it") {
                reference(1); reference(2)
                service.add(admin.loginId, mirror.id!!, "svn-alice", alice.loginId)
                service.add(admin.loginId, mirror.id!!, "svn-bob", alice.loginId)
                senders() shouldBe listOf("1" to alice.loginId, "2" to alice.loginId)
                val bobMapping = service.view(admin.loginId, mirror.id!!).mappings.single { it.sourceAuthor == "svn-bob" }
                service.remove(admin.loginId, mirror.id!!, bobMapping.id) shouldBe 1
                senders() shouldBe listOf("1" to alice.loginId, "2" to null)
            }

            it("rejects duplicates, unknown or deleted accounts, invalid authors and non-administrators") {
                service.add(admin.loginId, mirror.id!!, "svn-alice", alice.loginId)
                shouldThrow<MirrorFailure> { service.add(admin.loginId, mirror.id!!, "svn-alice", bob.loginId) }.code shouldBe "AUTHOR_MAPPING_EXISTS"
                shouldThrow<MirrorFailure> { service.add(admin.loginId, mirror.id!!, "svn-bob", "no-such-user") }.code shouldBe "AUTHOR_USER"
                users.save(bob.apply { state = UserState.DELETED })
                shouldThrow<MirrorFailure> { service.add(admin.loginId, mirror.id!!, "svn-bob", bob.loginId) }.code shouldBe "AUTHOR_USER"
                shouldThrow<MirrorFailure> { service.add(admin.loginId, mirror.id!!, " ", alice.loginId) }.code shouldBe "AUTHOR_INVALID"
                shouldThrow<MirrorFailure> { service.add(admin.loginId, mirror.id!!, "a\nb", alice.loginId) }.code shouldBe "AUTHOR_INVALID"
                shouldThrow<AccessDeniedException> { service.add(alice.loginId, mirror.id!!, "svn-bob", alice.loginId) }
                shouldThrow<AccessDeniedException> { service.view(alice.loginId, mirror.id!!) }
            }

            it("code browsing resolves a mirror author only through a mapping") {
                @Suppress("UNCHECKED_CAST")
                fun resolver(): (String) -> User? {
                    val repository = repositoryService.getRepository(projects.findById(project.id!!).get())
                    val field = repository.javaClass.getDeclaredField("userResolver").apply { isAccessible = true }
                    return field.get(repository) as (String) -> User?
                }
                resolver()(alice.loginId) shouldBe null
                resolver()("svn-alice") shouldBe null
                service.add(admin.loginId, mirror.id!!, "svn-alice", alice.loginId)
                resolver()("svn-alice")?.loginId shouldBe alice.loginId
            }

            it("serves the mapping page to a site administrator session and requires CSRF to change it") {
                mvc.perform(get("/site/repository-mirrors/${mirror.id}/authors").with(auth(admin)))
                    .andExpect(status().isOk).andReturn().response.contentAsString shouldContain project.name
                mvc.perform(get("/site/repository-mirrors/${mirror.id}/authors").with(auth(alice))).andExpect(status().isForbidden)
                mvc.perform(post("/site/repository-mirrors/${mirror.id}/authors").with(auth(admin))
                    .param("sourceAuthor", "svn-alice").param("loginId", alice.loginId)).andExpect(status().isForbidden)
                mvc.perform(post("/site/repository-mirrors/${mirror.id}/authors").with(auth(admin)).with(csrf())
                    .param("sourceAuthor", "svn-alice").param("loginId", alice.loginId)).andExpect(status().is3xxRedirection)
                mappings.findAllByMirrorIdOrderBySourceAuthorAsc(mirror.id!!).map { it.sourceAuthor } shouldBe listOf("svn-alice")
            }
        }
    }
}
