package com.github.yonaprojects.yona.web

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.domain.apitoken.*
import com.github.yonaprojects.yona.domain.project.*
import com.github.yonaprojects.yona.domain.role.*
import com.github.yonaprojects.yona.domain.user.*
import com.github.yonaprojects.yona.domain.vcs.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RepositoryMirrorAdminIntegrationSpec @Autowired constructor(
    private val wac: WebApplicationContext,
    private val projects: ProjectRepository,
    private val users: UserRepository,
    private val roles: RoleRepository,
    private val memberships: ProjectUserRepository,
    private val mirrors: RepositoryMirrorRepository,
    private val store: RepositoryMirrorStore,
    private val tokenRepository: ApiTokenRepository,
    private val transactionManager: PlatformTransactionManager,
    private val applicationSettings: RepositoryMirrorProperties
) : AbstractIntegrationTest() {
    private val mvc by lazy {
        MockMvcBuilders.webAppContextSetup(wac)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity()).build()
    }
    private val base = Files.createTempDirectory("mirror-admin-it-").toRealPath()
    private val settings = RepositoryMirrorProperties().apply {
        enabled = true
        egressRestricted = true
        nodeId = "writer"
        writerNodeId = "writer"
        proxyHost = "127.0.0.1"
        proxyPort = 19444
        allowedOrigins = listOf("https://example.org")
    }
    private val sourcePolicy = SvnMirrorSourcePolicy(settings)
    private val namespaceGuard = RepositoryNamespaceGuard(base.resolve("_data").toString(), base.toString())
    private val storage = RepositoryMirrorStorage(base.toString(), base.resolve("_git").toString(), base.resolve("_hg").toString(), namespaceGuard)
    private val service by lazy {
        RepositoryMirrorAdminService(settings, sourcePolicy, storage, projects, users, roles, memberships, mirrors, store, namespaceGuard, transactionManager)
    }
    private lateinit var admin: User
    private lateinit var member: User
    private val tokens = mutableListOf<ApiToken>()
    private fun auth(account: User, adminRole: Boolean = account.isSiteManager) = user(YonaUserDetails(
        id = account.id!!, loginId = account.loginId, passwordVal = "h", passwordSalt = "s",
        authoritiesVal = AuthorityUtils.createAuthorityList(if (adminRole) "ROLE_SITE_ADMIN" else "ROLE_ACTIVE")
    ))

    init {
        beforeSpec {
            admin = users.save(User(loginId = "mirror-it-admin", name = "Mirror administrator", state = UserState.SITE_ADMIN, email = "mirror-it-admin@example.org"))
            member = users.save(User(loginId = "mirror-it-member", name = "Mirror member", state = UserState.ACTIVE, email = "mirror-it-member@example.org"))
            roles.findById(RoleType.MANAGER.roleType).orElseGet { roles.save(Role(id = RoleType.MANAGER.roleType, name = "MANAGER")) }
        }
        afterSpec {
            tokens.forEach { tokenRepository.deleteById(it.id!!) }
            projects.findByOwner(admin.loginId).forEach { project ->
                mirrors.findByProjectId(project.id!!)?.let { mirrors.delete(it) }
                memberships.deleteAll(memberships.findByProjectId(project.id!!))
                projects.deleteById(project.id!!)
            }
            users.deleteById(member.id!!)
            users.deleteById(admin.id!!)
            base.toFile().deleteRecursively()
        }

        describe("dedicated mirror creation and safe admin operations") {
            it("persists a private SVN MIRROR and manager, reserves only a new path, and deduplicates replay") {
                val id = service.create(admin.loginId, "created", "https://example.org/private-source-path", null)
                val row = store.snapshot(id)
                row.project.repositoryMode shouldBe RepositoryMode.MIRROR
                row.project.vcs shouldBe "SUBVERSION"
                row.project.projectScope shouldBe ProjectScope.PRIVATE
                memberships.findByProjectIdAndUserId(row.project.id!!, admin.id!!).get().role!!.id shouldBe RoleType.MANAGER.roleType
                storage.directory(row).list()!!.toList() shouldBe emptyList()
                service.create(admin.loginId, "created", "https://example.org/private-source-path/", null) shouldBe id
                shouldThrow<MirrorFailure> { service.create(admin.loginId, "created", "https://example.org/changed", null) }
                shouldThrow<AccessDeniedException> { service.create(member.loginId, "denied", "https://example.org/svn", null) }
                row.lastVerifiedRevision shouldBe -1L
                row.lastIndexedRevision shouldBe -1L
            }

            it("does not convert Git or take a preexisting Git SVN or Mercurial namespace") {
                val git = projects.save(Project(owner = admin.loginId, name = "hosted-git", vcs = "GIT"))
                shouldThrow<MirrorFailure> { service.create(admin.loginId, "hosted-git", "https://example.org/svn", null) }
                projects.findById(git.id!!).get().vcs shouldBe "GIT"
                val targets = listOf(
                    "occupied-svn" to base.resolve("${admin.loginId}/occupied-svn"),
                    "occupied-git" to base.resolve("_git/${admin.loginId}/occupied-git.git"),
                    "occupied-hg" to base.resolve("_hg/${admin.loginId}/occupied-hg")
                )
                for ((name, path) in targets) {
                    Files.createDirectories(path)
                    Files.writeString(path.resolve("keep"), "existing repository")
                    shouldThrow<MirrorFailure> { service.create(admin.loginId, name, "https://example.org/svn", null) }
                    projects.findByOwnerAndName(admin.loginId, name).isPresent shouldBe false
                    Files.readString(path.resolve("keep")) shouldBe "existing repository"
                    Files.exists(namespaceGuard.markerPath(admin.loginId, name)) shouldBe false
                }
            }

            it("keeps the namespace reserved after a real mirror database transaction rolls back") {
                var mirrorId = 0L
                var projectId = 0L
                val name = "rolled-back"
                shouldThrow<IllegalStateException> {
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        mirrorId = service.create(admin.loginId, name, "https://example.org/svn", null)
                        projectId = mirrors.findById(mirrorId).orElseThrow().project.id!!
                        throw IllegalStateException("abort outer database transaction")
                    }
                }
                projects.findByOwnerAndName(admin.loginId, name).isPresent shouldBe false
                mirrors.findById(mirrorId).isPresent shouldBe false
                val target = base.resolve("${admin.loginId}/$name")
                val marker = namespaceGuard.markerPath(admin.loginId, name)
                val ownership = "yona-svn-mirror:$projectId:1\n$target\n"
                Files.readString(marker) shouldBe ownership
                Files.isDirectory(target) shouldBe true
                Files.writeString(target.resolve("keep"), "orphan must be inspected")
                shouldThrow<AccessDeniedException> { namespaceGuard.requireUnreserved(admin.loginId, name) }
                shouldThrow<MirrorFailure> { service.create(admin.loginId, name, "https://example.org/svn", null) }.code shouldBe "DIRECTORY_OWNERSHIP"
                projects.findByOwnerAndName(admin.loginId, name).isPresent shouldBe false
                Files.readString(marker) shouldBe ownership
                Files.readString(target.resolve("keep")) shouldBe "orphan must be inspected"
            }

            it("holds one same-name winner through the outer commit and resumes only its committed identity") {
                val name = "concurrent"
                val reserved = CountDownLatch(1)
                val release = CountDownLatch(1)
                val contenderStarted = CountDownLatch(1)
                val executor = Executors.newFixedThreadPool(2)
                try {
                    val winner = executor.submit<Long> {
                        TransactionTemplate(transactionManager).execute {
                            val id = service.create(admin.loginId, name, "https://example.org/svn", null)
                            reserved.countDown()
                            check(release.await(20, TimeUnit.SECONDS))
                            id
                        }!!
                    }
                    reserved.await(20, TimeUnit.SECONDS) shouldBe true
                    val contender = executor.submit<Result<Long>> {
                        contenderStarted.countDown()
                        runCatching { service.create(admin.loginId, name, "https://example.org/svn", null) }
                    }
                    try {
                        contenderStarted.await(20, TimeUnit.SECONDS) shouldBe true
                        // Do not wait for DB readers before releasing the winner: locking
                        // READ_COMMITTED may legitimately keep the competing request blocked.
                        shouldThrow<IllegalStateException> {
                            namespaceGuard.acquire(admin.loginId, name).use { }
                        }
                        winner.isDone shouldBe false
                    } finally { release.countDown() }
                    val id = winner.get(20, TimeUnit.SECONDS)
                    val competingResult = contender.get(20, TimeUnit.SECONDS)
                    when (val failure = competingResult.exceptionOrNull()) {
                        null -> competingResult.getOrThrow() shouldBe id
                        is MirrorFailure -> failure.code shouldBe "DIRECTORY_OWNERSHIP"
                        else -> throw failure
                    }
                    val row = store.snapshot(id)
                    projects.findByOwner(admin.loginId).count { it.name == name } shouldBe 1
                    mirrors.findByProjectId(row.project.id!!)?.id shouldBe id
                    val target = storage.directory(row).toPath()
                    Files.readString(namespaceGuard.markerPath(admin.loginId, name)) shouldBe "yona-svn-mirror:${row.project.id}:1\n$target\n"
                    service.create(admin.loginId, name, "https://example.org/svn", null) shouldBe id
                    shouldThrow<AccessDeniedException> { namespaceGuard.requireUnreserved(admin.loginId, name) }
                } finally {
                    release.countDown()
                    executor.shutdownNow()
                    executor.awaitTermination(20, TimeUnit.SECONDS)
                }
            }

            it("pause and repeated retry preserve R generation and cursors; credentials use logical refs") {
                val id = service.create(admin.loginId, "operations", "https://example.org/svn", null)
                val row = mirrors.findById(id).get().apply {
                    initialImportTargetRevision = 3
                    sourceRepositoryUuid = "source-uuid"
                    localRepositoryUuid = "local-uuid"
                    localYoungestRevision = 2
                    lastVerifiedRevision = 2
                    lastIndexedRevision = 1
                }
                mirrors.saveAndFlush(row)
                service.pause(admin.loginId, id)
                service.pause(admin.loginId, id)
                store.snapshot(id).enabled shouldBe false
                service.retry(admin.loginId, id)
                service.retry(admin.loginId, id)
                val resumed = store.snapshot(id)
                resumed.initialImportTargetRevision shouldBe 3L
                resumed.lastVerifiedRevision shouldBe 2L
                resumed.lastIndexedRevision shouldBe 1L
                resumed.generation shouldBe 1L
                val secret = base.resolve("account.properties")
                Files.writeString(secret, "username=readonly\npassword=hidden-password\n")
                Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-------"))
                settings.credentials = mapOf("operator-readonly" to secret.toString())
                service.setCredential(admin.loginId, id, "operator-readonly")
                store.snapshot(id).credentialRef shouldBe "operator-readonly"
                shouldThrow<MirrorFailure> { service.setCredential(admin.loginId, id, secret.toString()) }
                service.setCredential(admin.loginId, id, "")
                store.snapshot(id).credentialRef shouldBe null
            }
        }

        describe("actual administrator HTTP security and rendering with feature disabled") {
            it("renders masked status for every phase while off and denies all off mutations") {
                applicationSettings.enabled shouldBe false
                RepositoryMirrorStatus.entries.forEachIndexed { index, phase ->
                    val id = service.create(admin.loginId, "phase-$index", "https://example.org/secret-path-$index", null)
                    mirrors.findById(id).get().also { row ->
                        row.status = phase
                        row.lastErrorCode = "SECRET_ERROR_VALUE"
                        row.lastError = "password=hidden-password"
                        row.credentialRef = "secret-ref-never-render"
                        mirrors.saveAndFlush(row)
                    }
                }
                val page = mvc.perform(get("/site/repository-mirrors").with(auth(admin)))
                    .andExpect(status().isOk).andReturn().response.contentAsString
                page shouldContain "mirror-it-admin/phase-0"
                page shouldContain "https://example.org:443/[hidden]"
                page shouldNotContain "secret-path"
                page shouldNotContain "SECRET_ERROR_VALUE"
                page shouldNotContain "hidden-password"
                page shouldNotContain "secret-ref-never-render"
                val row = mirrors.findByProjectId(projects.findByOwnerAndName(admin.loginId, "phase-0").get().id!!)!!
                listOf("pause", "retry", "credential").forEach { operation ->
                    mvc.perform(post("/site/repository-mirrors/${row.id}/$operation").with(auth(admin)).with(csrf()))
                        .andExpect(status().is3xxRedirection)
                }
                store.snapshot(row.id!!).enabled shouldBe true
                mvc.perform(post("/site/repository-mirrors").with(auth(admin)).with(csrf())
                    .param("name", "off-denied").param("sourceUrl", "https://example.org/svn"))
                    .andExpect(status().is3xxRedirection)
                projects.findByOwnerAndName(admin.loginId, "off-denied").isPresent shouldBe false
            }

            it("rejects missing CSRF and a non-site-manager even if an authority claims administrator") {
                mvc.perform(post("/site/repository-mirrors/1/pause").with(auth(admin))).andExpect(status().isForbidden)
                mvc.perform(get("/site/repository-mirrors").with(auth(member, adminRole = true))).andExpect(status().isForbidden)
                mvc.perform(post("/site/repository-mirrors").with(auth(member, adminRole = true)).with(csrf())
                    .param("name", "direct-denied").param("sourceUrl", "https://example.org/svn"))
                    .andExpect(status().isForbidden)
            }

            it("rejects scoped API tokens and token-header attempts to bypass session CSRF") {
                val raw = "mirror-admin-scoped-token"
                val token = ApiToken(owner = admin, tokenHash = hashApiToken(raw), allRepositories = true, expiresAt = Instant.now().plusSeconds(3600))
                token.scopes.add(ApiTokenScope(apiToken = token, scopeGroup = ApiTokenScopeGroup.CODE, permission = ApiTokenPermission.WRITE))
                tokens += tokenRepository.save(token)
                mvc.perform(post("/site/repository-mirrors/1/pause").header("Yona-Token", raw)).andExpect(status().isForbidden)
                mvc.perform(post("/site/repository-mirrors/1/pause").with(auth(admin)).header("Yona-Token", "bogus"))
                    .andExpect(status().isForbidden)
            }
        }
    }
}
