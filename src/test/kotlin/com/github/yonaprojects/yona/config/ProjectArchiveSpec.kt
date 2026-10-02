package com.github.yonaprojects.yona.config

import com.github.yonaprojects.yona.config.security.AccessControl
import com.github.yonaprojects.yona.config.git.GitAuthorizationFilter
import com.github.yonaprojects.yona.config.svn.SvnAuthorizationFilter
import com.github.yonaprojects.yona.config.vcs.RepoAccessPolicy
import com.github.yonaprojects.yona.domain.enumeration.Operation
import com.github.yonaprojects.yona.domain.enumeration.ResourceType
import com.github.yonaprojects.yona.domain.project.*
import com.github.yonaprojects.yona.domain.role.Role
import com.github.yonaprojects.yona.domain.role.RoleType
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import com.github.yonaprojects.yona.domain.user.UserState
import com.github.yonaprojects.yona.domain.vcs.ArchivedProjectPreReceiveHook
import com.github.yonaprojects.yona.web.ProjectArchiveController
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.transport.ReceiveCommand
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.Optional

class ProjectArchiveSpec : DescribeSpec({
    beforeTest { org.springframework.security.core.context.SecurityContextHolder.clearContext() }
    afterTest { org.springframework.security.core.context.SecurityContextHolder.clearContext() }
    fun accessControl() = AccessControl(mockk(), mockk(relaxed = true), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk())
    fun archivedProject(scope: ProjectScope = ProjectScope.PUBLIC) = Project(
        id = 1, owner = "owner", name = "repo", projectScope = scope, vcs = "GIT", archivedAt = Instant.parse("2026-10-02T00:00:00Z")
    )

    it("archive rejects every project mutation including site managers without changing visibility") {
        val policy = accessControl()
        val project = archivedProject()
        val admin = User(id = 1, loginId = "admin", state = UserState.SITE_ADMIN)
        val member = User(id = 2, loginId = "member")
        member.projectUsers.add(ProjectUser(user = member, project = project, role = Role(id = RoleType.MEMBER.roleType)))
        for (scope in ProjectScope.entries) {
            project.projectScope = scope
            for (user in listOf(null, admin, member, User(id = 3, loginId = "outsider"))) {
                val before = policy.isAllowedToReadProject(user, project)
                project.archivedAt = null
                policy.isAllowedToReadProject(user, project) shouldBe before
                project.archivedAt = Instant.EPOCH
                for (operation in Operation.entries.filterNot { it == Operation.READ }) {
                    policy.isAllowed(user, project, operation) shouldBe false
                    policy.isAllowed(user, project, ResourceType.CODE, operation) shouldBe false
                }
                for (resource in ResourceType.entries) policy.isProjectResourceCreatable(user, project, resource) shouldBe false
            }
        }
        policy.canManageArchive(admin, project) shouldBe true
        policy.isAllowedToUpdateIssue(admin, project, admin.loginId) shouldBe false
        project.archivedAt = null
        policy.isAllowed(admin, project, Operation.UPDATE) shouldBe true
    }

    it("authors and site managers cannot mutate archived issues or attached files") {
        val project = archivedProject()
        val author = User(id = 2, loginId = "author")
        val admin = User(id = 3, loginId = "admin", state = UserState.SITE_ADMIN)
        val issue = com.github.yonaprojects.yona.domain.issue.Issue(id = 7, project = project, authorId = author.id)
        val issues = mockk<com.github.yonaprojects.yona.domain.issue.IssueRepository>()
        every { issues.findById(7) } returns Optional.of(issue)
        val policy = AccessControl(mockk(), mockk(relaxed = true), mockk(), mockk(), issues, mockk(), mockk(), mockk(), mockk())
        val file = com.github.yonaprojects.yona.domain.attachment.Attachment(containerType = ResourceType.ISSUE_POST, containerId = "7")
        for (user in listOf(author, admin)) {
            policy.isIssueCommentCreatable(user, project, issue) shouldBe false
            policy.isAllowed(user, project, issue, Operation.UPDATE) shouldBe false
            policy.isAllowedAttachment(user, file, Operation.DELETE) shouldBe false
            policy.isAllowedAttachment(user, file, Operation.READ) shouldBe true
        }
    }

    it("global logo deletion resolves its owning archived project") {
        val project = archivedProject()
        val projects = mockk<ProjectRepository>()
        val attachments = mockk<com.github.yonaprojects.yona.domain.attachment.AttachmentRepository>()
        every { projects.findById(1) } returns Optional.of(project)
        every { attachments.findById(9) } returns Optional.of(
            com.github.yonaprojects.yona.domain.attachment.Attachment(id = 9, containerType = ResourceType.PROJECT, containerId = "1"))
        val request = MockHttpServletRequest("POST", "/files/9")
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, mapOf("id" to "9"))
        val response = MockHttpServletResponse()
        ProjectArchiveInterceptor(projects, mockk(), attachments).preHandle(request, response, Any()) shouldBe false
        response.status shouldBe 403
    }

    it("only managers can archive or restore and repeated archive keeps its timestamp") {
        val project = archivedProject()
        val projects = mockk<ProjectRepository>()
        val users = mockk<UserRepository>()
        val manager = User(id = 1, loginId = "manager")
        manager.projectUsers.add(ProjectUser(user = manager, project = project, role = Role(id = RoleType.MANAGER.roleType)))
        every { projects.findByOwnerAndName("owner", "repo") } returns Optional.of(project)
        every { projects.save(project) } returns project
        every { users.findByLoginId("manager") } returns Optional.of(manager)
        every { users.findByLoginId("outsider") } returns Optional.of(User(id = 2, loginId = "outsider"))
        val controller = ProjectArchiveController(projects, users, accessControl())
        val auth = UsernamePasswordAuthenticationToken("manager", "unused")
        val original = project.archivedAt
        controller.archiveApi("owner", "repo", ProjectArchiveController.ArchiveRequest(true), auth)
        project.archivedAt shouldBe original
        shouldThrow<ResponseStatusException> {
            controller.archiveApi("owner", "repo", ProjectArchiveController.ArchiveRequest(false), UsernamePasswordAuthenticationToken("outsider", "unused"))
        }.statusCode.value() shouldBe 403
        project.archivedAt shouldBe original
        controller.archiveApi("owner", "repo", ProjectArchiveController.ArchiveRequest(false), auth)["archived"] shouldBe false
        project.isArchived shouldBe false
    }

    it("web numeric legacy and REST routes reject archived writes but preserve reads and restored writes") {
        val project = archivedProject()
        val projects = mockk<ProjectRepository>()
        every { projects.findById(1) } returns Optional.of(project)
        every { projects.findByOwnerAndNameOrPreviousPlace("owner", "repo") } returns Optional.of(project)
        val interceptor = ProjectArchiveInterceptor(projects, mockk(), mockk())
        for (variables in listOf(mapOf("projectId" to "1"), mapOf("owner" to "owner", "projectName" to "repo"), mapOf("owner" to "owner", "project" to "repo"))) {
            val request = MockHttpServletRequest("DELETE", "/owner/repo/issues/1")
            request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, variables)
            val response = MockHttpServletResponse()
            interceptor.preHandle(request, response, Any()) shouldBe false
            response.status shouldBe 403
            request.method = "GET"
            interceptor.preHandle(request, MockHttpServletResponse(), Any()) shouldBe true
            project.archivedAt = null
            request.method = "POST"
            interceptor.preHandle(request, MockHttpServletResponse(), Any()) shouldBe true
            project.archivedAt = Instant.EPOCH
        }
    }

    it("Git code wiki and LFS reject writes while upload-pack continues") {
        val project = archivedProject()
        val policy = mockk<RepoAccessPolicy>()
        every { policy.findProject("owner", "repo") } returns project
        every { policy.requiresAuth(project, false) } returns false
        val filter = GitAuthorizationFilter(policy)
        for (uri in listOf("/git/owner/repo.git/git-receive-pack", "/git/owner/repo.wiki.git/git-receive-pack", "/git-lfs/owner/repo/objects/abc")) {
            val response = MockHttpServletResponse()
            filter.doFilter(MockHttpServletRequest(if (uri.startsWith("/git-lfs")) "PUT" else "POST", uri), response) { _, _ -> error("write reached repository") }
            response.status shouldBe 403
        }
        var readReachedRepository = false
        filter.doFilter(MockHttpServletRequest("POST", "/git/owner/repo.git/git-upload-pack"), MockHttpServletResponse()) { _, _ -> readReachedRepository = true }
        readReachedRepository shouldBe true
    }

    it("SVN permits DAV reads and rejects checkout and commit mutations") {
        val project = archivedProject().apply { vcs = "SVN" }
        val policy = mockk<RepoAccessPolicy>()
        every { policy.findProject("owner", "repo") } returns project
        every { policy.requiresAuth(project, false) } returns false
        val filter = SvnAuthorizationFilter(policy)
        for (method in listOf("MKACTIVITY", "CHECKOUT", "PUT", "MERGE", "DELETE", "PROPPATCH")) {
            val response = MockHttpServletResponse()
            filter.doFilter(MockHttpServletRequest(method, "/svn/owner/repo"), response) { _, _ -> error("write reached SVN") }
            response.status shouldBe 403
        }
        for (method in listOf("GET", "HEAD", "OPTIONS", "PROPFIND", "REPORT")) {
            var reached = false
            filter.doFilter(MockHttpServletRequest(method, "/svn/owner/repo"), MockHttpServletResponse()) { _, _ -> reached = true }
            reached shouldBe true
        }
    }

    it("pre-receive rechecks archive after authentication and protects every ref including tags") {
        val projects = mockk<ProjectRepository>()
        val hook = ArchivedProjectPreReceiveHook(1, projects)
        var archived = true
        every { projects.existsByIdAndArchivedAtIsNotNull(1) } answers { archived }
        for (ref in listOf("refs/heads/main", "refs/tags/v1", "refs/notes/review")) {
            val command = ReceiveCommand(ObjectId.zeroId(), ObjectId.fromString("1".repeat(40)), ref)
            hook.onPreReceive(mockk(), listOf(command))
            command.result shouldBe ReceiveCommand.Result.REJECTED_OTHER_REASON
        }
        archived = false
        val restored = ReceiveCommand(ObjectId.zeroId(), ObjectId.fromString("1".repeat(40)), "refs/heads/main")
        hook.onPreReceive(mockk(), listOf(restored))
        restored.result shouldBe ReceiveCommand.Result.NOT_ATTEMPTED
    }
})
