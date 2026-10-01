package com.github.yonaprojects.yona.web

import com.github.yonaprojects.yona.config.security.AccessControl
import com.github.yonaprojects.yona.domain.enumeration.Operation
import com.github.yonaprojects.yona.domain.issue.Issue
import com.github.yonaprojects.yona.domain.issue.IssueRepository
import com.github.yonaprojects.yona.domain.organization.Organization
import com.github.yonaprojects.yona.domain.organization.OrganizationRepository
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.project.ProjectScope
import com.github.yonaprojects.yona.domain.project.ProjectUserRepository
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import com.github.yonaprojects.yona.domain.vcs.Commit
import com.github.yonaprojects.yona.domain.vcs.PlayRepository
import com.github.yonaprojects.yona.domain.vcs.RepositoryService
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.context.support.StaticMessageSource
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.Locale
import java.util.Optional

class MarkdownReferenceControllerSpec : DescribeSpec({
    val projects = mockk<ProjectRepository>()
    val issues = mockk<IssueRepository>()
    val users = mockk<UserRepository>()
    val organizations = mockk<OrganizationRepository>()
    val repositories = mockk<RepositoryService>()
    val access = mockk<AccessControl>()
    val members = mockk<ProjectUserRepository>()
    val messages = StaticMessageSource().apply {
        addMessage("issue.state.open", Locale.KOREAN, "열림")
        addMessage("issue.state.closed", Locale.KOREAN, "닫힘")
    }
    val mvc = MockMvcBuilders.standaloneSetup(
        MarkdownReferenceController(projects, issues, users, organizations, repositories, access, members, messages)
    ).build()
    val context = Project(id = 1L, owner = "owner", name = "public", projectScope = ProjectScope.PUBLIC)
    val privateProject = Project(id = 2L, owner = "owner", name = "private", projectScope = ProjectScope.PRIVATE)
    val endpoint = "/api/owner/public/markdown/references/resolve"

    beforeTest {
        clearMocks(projects, issues, users, organizations, repositories, access, members)
        every { projects.findByOwnerAndNameOrPreviousPlace("owner", "public") } returns Optional.of(context)
        every { access.isAllowed(null, context, Operation.READ) } returns true
        every { organizations.findByName(any()) } returns Optional.empty()
    }

    describe("permission-checked Markdown reference metadata") {
        it("rejects an unreadable source project before any reference lookup") {
            every { access.isAllowed(null, context, Operation.READ) } returns false
            mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON)
                .content("""{"items":[{"type":"issue","value":"#1"}]}"""))
                .andExpect(status().isForbidden)
            verify(exactly = 0) { issues.findByProjectAndNumber(any(), any()) }
        }

        it("does not expose a private cross-project issue, project, or commit") {
            every { projects.findByOwnerAndName("owner", "private") } returns Optional.of(privateProject)
            every { access.isAllowed(null, privateProject, Operation.READ) } returns false
            mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content("""{"items":[
                {"type":"issue","value":"owner/private#1"},
                {"type":"project","value":"owner/private"},
                {"type":"commit","value":"owner/private@abcdef0"}
            ]}"""))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items").isEmpty)
            verify(exactly = 0) { issues.findByProjectAndNumber(any(), any()) }
            verify(exactly = 0) { repositories.getRepository(any<Project>()) }
        }

        it("does not expose commits from a public project with members-only code") {
            val restricted = Project(id = 3L, owner = "owner", name = "restricted",
                projectScope = ProjectScope.PUBLIC, isCodeAccessibleMemberOnly = true)
            every { projects.findByOwnerAndName("owner", "restricted") } returns Optional.of(restricted)
            every { access.isAllowed(null, restricted, Operation.READ) } returns true
            mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON)
                .content("""{"items":[{"type":"commit","value":"owner/restricted@abcdef0"}]}"""))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items").isEmpty)
            verify(exactly = 0) { repositories.getRepository(any<Project>()) }
        }

        it("checks issue READ independently of project READ and leaves denied titles out") {
            val issue = Issue(id = 9L, project = context, number = 1L, title = "hidden title", body = "")
            every { issues.findByProjectAndNumber(context, 1L) } returns issue
            every { access.isAllowed(null, context, issue, Operation.READ) } returns false
            mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON)
                .content("""{"items":[{"type":"issue","value":"#1"}]}"""))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items").isEmpty)
        }

        it("deduplicates readable issue references and returns titles as data, never HTML") {
            val issue = Issue(id = 9L, project = context, number = 1L, title = "<img src=x onerror=alert(1)>", body = "")
            every { issues.findByProjectAndNumber(context, 1L) } returns issue
            every { access.isAllowed(null, context, issue, Operation.READ) } returns true
            mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content("""{"items":[
                {"type":"issue","value":"#1"},{"type":"issue","value":"#1"}
            ]}"""))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].key").value("issue:#1"))
                .andExpect(jsonPath("$.items[0].href").value("/owner/public/issue/1"))
                .andExpect(jsonPath("$.items[0].label").value("#1.<img src=x onerror=alert(1)>"))
            verify(exactly = 1) { issues.findByProjectAndNumber(context, 1L) }
        }

        // Legacy AutoLinkRenderer parity: localized state text, fork shorthand, user popover, org/user kind.
        it("returns the issue state label localized like the legacy issue.state.* message") {
            val issue = Issue(id = 9L, project = context, number = 1L, title = "title", body = "")
            every { issues.findByProjectAndNumber(context, 1L) } returns issue
            every { access.isAllowed(null, context, issue, Operation.READ) } returns true
            mvc.perform(post(endpoint).locale(Locale.KOREAN).contentType(MediaType.APPLICATION_JSON)
                .content("""{"items":[{"type":"issue","value":"#1"}]}"""))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items[0].state").value("open"))
                .andExpect(jsonPath("$.items[0].stateLabel").value("열림"))
        }

        it("resolves the fork shorthand owner#N and owner@sha against the same-named project") {
            val fork = Project(id = 4L, owner = "fork", name = "public", projectScope = ProjectScope.PUBLIC)
            val issue = Issue(id = 10L, project = fork, number = 1L, title = "forked", body = "")
            val repository = mockk<PlayRepository>()
            val commit = mockk<Commit>()
            every { projects.findByOwnerAndName("fork", "public") } returns Optional.of(fork)
            every { access.isAllowed(null, fork, Operation.READ) } returns true
            every { issues.findByProjectAndNumber(fork, 1L) } returns issue
            every { access.isAllowed(null, fork, issue, Operation.READ) } returns true
            every { repositories.getRepository(fork) } returns repository
            every { repository.getCommit("abcdef0") } returns commit
            every { commit.getId() } returns "abcdef0123456789"
            every { commit.getShortId() } returns "abcdef0"
            mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content("""{"items":[
                {"type":"issue","value":"fork#1"},{"type":"commit","value":"fork@abcdef0"}
            ]}"""))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].href").value("/fork/public/issue/1"))
                .andExpect(jsonPath("$.items[0].label").value("fork#1.forked"))
                .andExpect(jsonPath("$.items[1].href").value("/fork/public/commit/abcdef0123456789"))
                .andExpect(jsonPath("$.items[1].label").value("fork@abcdef0"))
        }

        it("marks users with the legacy name/login popover text and organizations as org") {
            val user = User(id = 7L, loginId = "hong", name = "홍길동 (dev)", email = "hong@example.com")
            every { users.findByLoginId("hong") } returns Optional.of(user)
            every { organizations.findByName("team") } returns Optional.of(Organization(id = 2L, name = "team"))
            every { access.isAllowed(null, any<Organization>(), Operation.READ) } returns true
            mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content("""{"items":[
                {"type":"user","value":"@hong"},{"type":"user","value":"@team"}
            ]}"""))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items[0].kind").value("user"))
                .andExpect(jsonPath("$.items[0].label").value("@홍길동"))
                .andExpect(jsonPath("$.items[0].popover").value("홍길동 (dev) hong"))
                .andExpect(jsonPath("$.items[1].kind").value("org"))
                .andExpect(jsonPath("$.items[1].label").value("@team"))
        }

        it("omits nonexistent and malformed references without repository revision evaluation") {
            every { issues.findByProjectAndNumber(context, 99L) } returns null
            mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content("""{"items":[
                {"type":"issue","value":"#99"},{"type":"issue","value":"#999999999999999999999"},
                {"type":"commit","value":"HEAD~1"},{"type":"project","value":"../private"},
                {"type":"user","value":"@<script>"}
            ]}"""))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.items").isEmpty)
            verify(exactly = 0) { repositories.getRepository(any<Project>()) }
        }

        it("rejects oversized batches, oversized tokens, and unknown reference types") {
            val oversized = (1..101).joinToString(",") { """{"type":"issue","value":"#$it"}""" }
            for (body in listOf(
                """{"items":[$oversized]}""",
                """{"items":[{"type":"user","value":"${"a".repeat(201)}"}]}""",
                """{"items":[{"type":"html","value":"#1"}]}"""
            )) {
                mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest)
            }
            verify(exactly = 0) { projects.findByOwnerAndNameOrPreviousPlace(any(), any()) }
        }
    }
})
