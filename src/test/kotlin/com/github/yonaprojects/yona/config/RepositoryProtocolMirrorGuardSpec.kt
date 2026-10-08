package com.github.yonaprojects.yona.config

import com.github.yonaprojects.yona.config.git.DeployKeyAuthenticationToken
import com.github.yonaprojects.yona.config.git.GitAuthorizationFilter
import com.github.yonaprojects.yona.config.hg.HgAuthorizationFilter
import com.github.yonaprojects.yona.config.ssh.HgSshProtocolHandler
import com.github.yonaprojects.yona.config.svn.SvnAuthorizationFilter
import com.github.yonaprojects.yona.config.vcs.RepoAccessPolicy
import com.github.yonaprojects.yona.domain.deploykey.DeployKey
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.project.RepositoryMode
import com.github.yonaprojects.yona.domain.sshkey.SshAuthPrincipal
import com.github.yonaprojects.yona.domain.sshkey.SshAuthServiceImpl
import com.github.yonaprojects.yona.domain.sshkey.SshCommandAuthorization
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.vcs.RepositoryKind
import com.github.yonaprojects.yona.domain.vcs.RepositoryWriteGuard
import com.github.yonaprojects.yona.domain.vcs.RepositoryWritePreReceiveHook
import com.github.yonaprojects.yona.web.HgController
import io.github.search5.hg4j.api.HgHook
import io.github.search5.hg4j.transport.HgHttpWireServer
import io.github.search5.hg4j.transport.HgSshWireServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.servlet.FilterChain
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.transport.ReceiveCommand
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.core.context.SecurityContextHolder
import java.io.IOException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Optional

class RepositoryProtocolMirrorGuardSpec : DescribeSpec({
    val projects = mockk<ProjectRepository>()
    val policy = mockk<RepoAccessPolicy>()
    val guard = RepositoryWriteGuard(projects)
    // The entity deliberately stays HOSTED: only the fresh scalar result changes.
    val project = Project(owner = "owner", name = "repo").apply { id = 42L }
    var persistedMode = RepositoryMode.HOSTED
    val chain = mockk<FilterChain>(relaxed = true)
    val git = GitAuthorizationFilter(policy, guard)
    val svn = SvnAuthorizationFilter(policy, guard)
    val hg = HgAuthorizationFilter(policy, guard)

    beforeTest {
        clearMocks(projects, policy, chain)
        persistedMode = RepositoryMode.HOSTED
        project.vcs = "GIT"
        every { projects.findRepositoryModeById(42L) } answers { persistedMode }
        every { projects.findByOwnerAndNameOrPreviousPlace("owner", "repo") } returns Optional.of(project)
        every { policy.findProject("owner", "repo") } returns project
        every { policy.requiresAuth(project, any()) } answers { secondArg<Boolean>() }
        every { policy.isMember(project, any()) } returns true
        every { policy.isGuestUser(any()) } returns false
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            "admin", "", AuthorityUtils.createAuthorityList("ROLE_SITE_ADMIN")
        )
    }
    afterTest { SecurityContextHolder.clearContext() }

    fun request(method: String, uri: String, query: String? = null): MockHttpServletResponse {
        val req = MockHttpServletRequest(method, uri)
        req.queryString = query
        if (query == "service=git-receive-pack") req.addParameter("service", "git-receive-pack")
        val response = MockHttpServletResponse()
        when {
            uri.startsWith("/git") -> git.doFilter(req, response, chain)
            uri.startsWith("/svn") -> {
                project.vcs = "SUBVERSION"
                svn.doFilter(req, response, chain)
            }
            else -> {
                project.vcs = "MERCURIAL"
                hg.doFilter(req, response, chain)
            }
        }
        return response
    }

    describe("HTTP writes use persisted mode independently of feature activation and admin authority") {
        val writes = listOf(
            Triple("POST", "/git/owner/repo.git/git-receive-pack", null),
            Triple("GET", "/git/owner/repo.git/info/refs", "service=git-receive-pack"),
            Triple("PUT", "/git-lfs/owner/repo/objects/abc", null),
            Triple("POST", "/hg/owner/repo", "cmd=unbundle"),
            Triple("POST", "/hg/owner/repo", "cmd=pushkey&namespace=bookmarks"),
            Triple("POST", "/hg/owner/repo", "cmd=pushkey&namespace=phases"),
            Triple("POST", "/hg/owner/repo", "cmd=batch&cmds=pushkey%20namespace%3Dbookmarks%2Ckey%3Dmain%2Cold%3D%2Cnew%3Dabc"),
            Triple("GET", "/hg/owner/repo", "cmd=batch&cmds=heads"),
            Triple("POST", "/hg/owner/repo", "cmd=heads&%63md=%62atch"),
            Triple("POST", "/hg/owner/repo", "cmd=heads&%63md=push%6bey"),
            Triple("POST", "/hg/owner/repo/api/exp/ro/pushkey", null),
            Triple("POST", "/hg/owner/repo/api/exp/ro/multirequest", null),
            Triple("POST", "/hg/owner/repo/api/exp/rw/pushkey", null)
        ) + listOf("PUT", "DELETE", "MKCOL", "COPY", "MOVE", "MERGE", "PROPPATCH", "LOCK", "UNLOCK", "CHECKOUT", "MKACTIVITY")
            .map { Triple(it, "/svn/owner/repo/trunk", null) }
        for ((method, uri, query) in writes) {
            it("denies MIRROR $method $uri $query for users and writable deploy keys; preserves HOSTED") {
                request(method, uri, query).status shouldBe 200
                clearMocks(chain)
                persistedMode = RepositoryMode.MIRROR
                request(method, uri, query).status shouldBe 403
                SecurityContextHolder.getContext().authentication = DeployKeyAuthenticationToken.authenticated(
                    DeployKey(project = project, readOnly = false)
                )
                request(method, uri, query).status shouldBe 403
                verify(exactly = 0) { chain.doFilter(any(), any()) }
                project.repositoryMode shouldBe RepositoryMode.HOSTED
            }
        }
        it("keeps authenticated HTTP wiki writes available but not unauthorized wiki writes") {
            persistedMode = RepositoryMode.MIRROR
            request("POST", "/git/owner/repo.wiki.git/git-receive-pack").status shouldBe 200
            request("PUT", "/git-lfs/owner/repo.wiki.git/objects/abc").status shouldBe 200
            every { policy.isMember(project, any()) } returns false
            request("POST", "/git/owner/repo.wiki.git/git-receive-pack").status shouldBe 403
        }
        it("keeps mirror read protocols open") {
            persistedMode = RepositoryMode.MIRROR
            request("POST", "/git/owner/repo.git/git-upload-pack").status shouldBe 200
            request("GET", "/hg/owner/repo", "cmd=getbundle").status shouldBe 200
            request("REPORT", "/svn/owner/repo/trunk").status shouldBe 200
            verify(exactly = 3) { chain.doFilter(any(), any()) }
        }
        it("preserves HOSTED authentication and membership failures") {
            SecurityContextHolder.clearContext()
            request("POST", "/git/owner/repo.git/git-receive-pack").status shouldBe 401
            SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
                "outsider", "", AuthorityUtils.createAuthorityList("ROLE_USER")
            )
            every { policy.isMember(project, any()) } returns false
            request("POST", "/hg/owner/repo", "cmd=pushkey").status shouldBe 403
            request("PUT", "/svn/owner/repo/trunk").status shouldBe 403
            verify(exactly = 0) { chain.doFilter(any(), any()) }
        }
    }

    describe("Git SSH and final receive hook") {
        it("denies user and writable deploy-key receive-pack without adding SSH wiki support") {
            val service = SshAuthServiceImpl(mockk(), mockk(), mockk(), projects, policy, guard, "/git", "/hg")
            val user = User(loginId = "admin")
            val principals = listOf(
                SshAuthPrincipal.SshKeyPrincipal(user, mockk()),
                SshAuthPrincipal.DeployKeyPrincipal(DeployKey(project = project, readOnly = false))
            )
            for (principal in principals) {
                persistedMode = RepositoryMode.HOSTED
                service.authorizeGitCommand(principal, "git-receive-pack 'owner/repo.git'").allowed shouldBe true
                persistedMode = RepositoryMode.MIRROR
                service.authorizeGitCommand(principal, "git-receive-pack 'owner/repo.git'").allowed shouldBe false
                service.authorizeGitCommand(principal, "git-upload-pack 'owner/repo.git'").allowed shouldBe true
            }
            every { projects.findByOwnerAndNameOrPreviousPlace("owner", "repo.wiki") } returns Optional.empty()
            service.authorizeGitCommand(principals.first(), "git-receive-pack 'owner/repo.wiki.git'").allowed shouldBe false
        }
        it("rechecks all refs at receive time while preserving prior failures and wiki writes") {
            val hook = RepositoryWritePreReceiveHook(project, guard)
            fun command(ref: String) = ReceiveCommand(ObjectId.zeroId(), ObjectId.fromString("1".repeat(40)), ref)
            val hosted = command("refs/heads/main")
            hook.onPreReceive(mockk(), listOf(hosted))
            hosted.result shouldBe ReceiveCommand.Result.NOT_ATTEMPTED
            persistedMode = RepositoryMode.MIRROR
            val branch = command("refs/heads/main")
            val tag = command("refs/tags/v1")
            val rejected = command("refs/yobi/internal").apply {
                setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON, "reserved")
            }
            hook.onPreReceive(mockk(), listOf(branch, tag, rejected))
            branch.result shouldBe ReceiveCommand.Result.REJECTED_OTHER_REASON
            tag.result shouldBe ReceiveCommand.Result.REJECTED_OTHER_REASON
            rejected.message shouldBe "reserved"
            val wiki = command("refs/heads/main")
            RepositoryWritePreReceiveHook(project, guard, RepositoryKind.WIKI).onPreReceive(mockk(), listOf(wiki))
            wiki.result shouldBe ReceiveCommand.Result.NOT_ATTEMPTED
        }
    }

    describe("Hg mutation hooks do not trust session mode or pushkey namespace") {
        it("refuses unsupported MIRROR SSH sessions before a nested batch can reach hg4j") {
            val service = SshAuthServiceImpl(mockk(), mockk(), mockk(), projects, policy, guard, "/git", "/hg")
            val principal = SshAuthPrincipal.DeployKeyPrincipal(DeployKey(project = project, readOnly = false))
            val hosted = service.authorizeHgCommand(principal, "hg -R owner/repo serve --stdio")
            hosted.allowed shouldBe true
            persistedMode = RepositoryMode.MIRROR
            service.authorizeHgCommand(principal, "hg -R owner/repo serve --stdio").allowed shouldBe false
            val handler = HgSshProtocolHandler(mockk(), mockk(), mockk(), projects, mockk(), mockk(), mockk(), mockk(), guard)
            val commands = "pushkey namespace=bookmarks,key=main,old=,new=abc"
            val input = ByteArrayInputStream("batch\ncmds ${commands.length}\n$commands* 0\n".toByteArray())
            val originalSize = input.available()
            shouldThrow<IOException> { handler.handle(hosted, input, ByteArrayOutputStream()) }
            input.available() shouldBe originalSize
        }
        it("refuses HTTP nested batches before opening the native repository") {
            val controller = HgController("/nonexistent", projects, mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), guard)
            persistedMode = RepositoryMode.MIRROR
            val req = MockHttpServletRequest("POST", "/hg/owner/repo")
            req.queryString = "cmd=heads&%63md=%62atch"
            req.addHeader("X-HgArg-1", "cmds=pushkey%20namespace%3Dbookmarks%2Ckey%3Dmain%2Cold%3D%2Cnew%3Dabc")
            val response = MockHttpServletResponse()
            controller.service("owner", "repo", req, response)
            response.status shouldBe 403
        }
        it("registers fresh SSH checks before changegroups and every pushkey namespace") {
            val server = mockk<HgSshWireServer>()
            val changegroup = slot<HgHook>()
            val pushkey = slot<HgHook>()
            every { server.registerPreChangegroupHook(capture(changegroup)) } returns server
            every { server.registerPrePushkeyHook(capture(pushkey)) } returns server
            val handler = HgSshProtocolHandler(mockk(), mockk(), mockk(), projects, mockk(), mockk(), mockk(), mockk(), guard)
            val authorization = SshCommandAuthorization(allowed = true, isWrite = true, project = project)
            handler.registerWriteGuards(server, authorization)
            changegroup.captured.run(emptyMap()) shouldBe true
            pushkey.captured.run(mapOf("namespace" to "bookmarks")) shouldBe true
            persistedMode = RepositoryMode.MIRROR
            shouldThrow<IOException> { changegroup.captured.run(emptyMap()) }
            for (namespace in listOf("bookmarks", "phases", "obsolete", "custom", "")) {
                shouldThrow<IOException> { pushkey.captured.run(mapOf("namespace" to namespace)) }
            }
            persistedMode = RepositoryMode.HOSTED
            handler.registerWriteGuards(server, authorization.copy(isWrite = false))
            shouldThrow<IOException> { changegroup.captured.run(emptyMap()) }
            shouldThrow<IOException> { pushkey.captured.run(mapOf("namespace" to "phases")) }
        }
        it("registers fresh HTTP checks at direct mutation hooks") {
            val server = mockk<HgHttpWireServer>()
            val changegroup = slot<HgHook>()
            val pushkey = slot<HgHook>()
            every { server.registerPreChangegroupHook(capture(changegroup)) } returns server
            every { server.registerPrePushkeyHook(capture(pushkey)) } returns server
            val controller = HgController("/hg", projects, mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), guard)
            controller.registerWriteGuards(server, "owner", "repo")
            changegroup.captured.run(emptyMap()) shouldBe true
            persistedMode = RepositoryMode.MIRROR
            shouldThrow<IOException> { changegroup.captured.run(emptyMap()) }
            for (namespace in listOf("bookmarks", "phases", "obsolete", "custom", "")) {
                shouldThrow<IOException> { pushkey.captured.run(mapOf("namespace" to namespace)) }
            }
        }
    }
})
