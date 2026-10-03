package com.github.yonaprojects.yona.web

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.domain.enumeration.State
import com.github.yonaprojects.yona.domain.issue.Issue
import com.github.yonaprojects.yona.domain.issue.IssueRepository
import com.github.yonaprojects.yona.domain.issue.IssueService
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.project.ProjectScope
import com.github.yonaprojects.yona.domain.project.ProjectUser
import com.github.yonaprojects.yona.domain.project.ProjectUserRepository
import com.github.yonaprojects.yona.domain.role.Role
import com.github.yonaprojects.yona.domain.role.RoleRepository
import com.github.yonaprojects.yona.domain.role.RoleType
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.jsoup.Jsoup
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import java.time.Instant

@Transactional
class PinnedIssueSpec @Autowired constructor(
    private val context: WebApplicationContext,
    private val projects: ProjectRepository,
    private val users: UserRepository,
    private val memberships: ProjectUserRepository,
    private val roles: RoleRepository,
    private val issues: IssueRepository,
    private val issueService: IssueService
) : AbstractIntegrationTest() {
    private val mvc by lazy { MockMvcBuilders.webAppContextSetup(context).build() }

    init {
        describe("Shared project pins") {
            it("persists manager pins, shares them with another user, and denies non-managers and drafts") {
                val manager = users.save(User(loginId = "pin-manager", name = "Manager", email = "pin-manager@example.test"))
                val viewer = users.save(User(loginId = "pin-viewer", name = "Viewer", email = "pin-viewer@example.test"))
                val project = projects.save(Project(owner = manager.loginId!!, name = "pin-permissions", projectScope = ProjectScope.PUBLIC))
                val role = roles.findById(RoleType.MANAGER.roleType).orElseGet {
                    roles.save(Role(id = RoleType.MANAGER.roleType, name = "manager"))
                }
                manager.projectUsers.add(memberships.save(ProjectUser(user = manager, project = project, role = role)))
                val memberRole = roles.findById(RoleType.MEMBER.roleType).orElseGet {
                    roles.save(Role(id = RoleType.MEMBER.roleType, name = "member"))
                }
                viewer.projectUsers.add(memberships.save(ProjectUser(user = viewer, project = project, role = memberRole)))
                val issue = issues.save(Issue(project = project, number = 1, title = "Shared announcement", authorId = viewer.id))
                issues.save(Issue(project = project, number = 2, title = "Draft announcement", state = State.DRAFT, isDraft = true))
                val api = "/api/v1/projects/${project.owner}/${project.name}/issues"
                val managerAuth = UsernamePasswordAuthenticationToken(manager.loginId, "password")
                val viewerAuth = UsernamePasswordAuthenticationToken(viewer.loginId, "password")

                mvc.perform(put("$api/1/pin").principal(viewerAuth).contentType(MediaType.APPLICATION_JSON).content("{\"pinned\":true}"))
                    .andExpect(status().isForbidden)
                mvc.perform(put("$api/1/pin").contentType(MediaType.APPLICATION_JSON).content("{\"pinned\":true}"))
                    .andExpect(status().isUnauthorized)
                mvc.perform(put("$api/2/pin").principal(managerAuth).contentType(MediaType.APPLICATION_JSON).content("{\"pinned\":true}"))
                    .andExpect(status().isBadRequest)
                mvc.perform(put("$api/1/pin").principal(managerAuth).contentType(MediaType.APPLICATION_JSON).content("{\"pinned\":true}"))
                    .andExpect(status().isOk)
                val pinnedAt = issues.findById(issue.id!!).orElseThrow().pinnedAt
                pinnedAt shouldNotBe null
                val body = mvc.perform(get("/${project.owner}/${project.name}/issues").principal(viewerAuth))
                    .andExpect(status().isOk).andReturn().response.contentAsString
                Jsoup.parse(body).select("#pinned-issues li a").text() shouldBe "#1 Shared announcement"
                Jsoup.parse(body).select("[data-item=issue-item]").size shouldBe 0
                mvc.perform(put("$api/1/pin").principal(managerAuth).contentType(MediaType.APPLICATION_JSON).content("{\"pinned\":true}"))
                    .andExpect(status().isOk)
                issues.findById(issue.id!!).orElseThrow().pinnedAt shouldBe pinnedAt
                mvc.perform(post("/${project.owner}/${project.name}/issue/1/pin").principal(managerAuth).param("pinned", "false"))
                    .andExpect(status().isSeeOther)
                    .andExpect(header().string("Location", "/${project.owner}/${project.name}/issue/1"))
                issues.findById(issue.id!!).orElseThrow().pinnedAt shouldBe null
                for (pin in listOf(true, false)) {
                    mvc.perform(post("/yona/${project.owner}/${project.name}/issue/1/pin")
                        .contextPath("/yona").principal(managerAuth).param("pinned", pin.toString()))
                        .andExpect(status().isSeeOther)
                        .andExpect(header().string("Location", "/yona/${project.owner}/${project.name}/issue/1"))
                    (issues.findById(issue.id!!).orElseThrow().pinnedAt != null) shouldBe pin
                }
                val privateProject = projects.save(Project(owner = manager.loginId!!, name = "pin-hidden", projectScope = ProjectScope.PRIVATE))
                issues.save(Issue(project = privateProject, number = 1, title = "Private pin", pinnedAt = Instant.now()))
                mvc.perform(put("/api/v1/projects/${privateProject.owner}/${privateProject.name}/issues/1/pin")
                    .principal(viewerAuth).contentType(MediaType.APPLICATION_JSON).content("{\"pinned\":false}"))
                    .andExpect(status().isNotFound)
            }

            it("applies filters to pins without duplicating paginated rows or revealing private or draft issues") {
                users.save(User(loginId = "pin-filter-owner", name = "Owner", email = "pin-filter-owner@example.test"))
                val project = projects.save(Project(owner = "pin-filter-owner", name = "pin-filter", projectScope = ProjectScope.PUBLIC))
                val privateProject = projects.save(Project(owner = "pin-private-owner", name = "pin-private", projectScope = ProjectScope.PRIVATE))
                fun save(number: Long, title: String, pin: Boolean = false, state: State = State.OPEN, draft: Boolean = false, target: Project = project) =
                    issues.save(Issue(project = target, number = number, title = title, state = state, isDraft = draft,
                        pinnedAt = if (pin) Instant.parse("2026-01-01T00:00:00Z") else null))
                save(1, "needle pinned", pin = true)
                save(2, "needle first")
                save(3, "needle second")
                save(4, "unmatched pin", pin = true)
                save(5, "needle closed", pin = true, state = State.CLOSED)
                save(6, "needle draft", pin = true, draft = true)
                save(7, "needle private", pin = true, target = privateProject)

                for (page in 0..1) {
                    val result = mvc.perform(get("/${project.owner}/${project.name}/issues")
                        .param("filter", "needle").param("itemsPerPage", "1").param("page", page.toString())
                        .param("orderBy", "number").param("orderDir", "asc"))
                        .andExpect(status().isOk).andReturn()
                    val html = result.response.contentAsString
                    val dom = Jsoup.parse(html)
                    dom.select("#pinned-issues li a").text() shouldBe "#1 needle pinned"
                    dom.select("#pinned-issues h3 .num-badge").map { it.text() } shouldBe listOf("1", "2")
                    dom.select("[data-item=issue-item] .post-id").text() shouldBe "#${page + 2}"
                    dom.select("#pagination").attr("data-total") shouldBe "2"
                    html shouldNotContain "unmatched pin"
                    html shouldNotContain "needle closed"
                    html shouldNotContain "needle draft"
                    html shouldNotContain "needle private"
                }
                mvc.perform(get("/${privateProject.owner}/${privateProject.name}/issues"))
                    .andReturn().response.contentAsString shouldNotContain "needle private"
            }

            it("clears parent and child pins on transfer, rejects stale source writes, and allows deleting a pinned issue") {
                val mover = users.save(User(loginId = "pin-mover", name = "Mover", email = "pin-mover@example.test"))
                val source = projects.save(Project(owner = "pin-mover", name = "pin-source", projectScope = ProjectScope.PRIVATE))
                val target = projects.save(Project(owner = "pin-mover", name = "pin-target", projectScope = ProjectScope.PUBLIC))
                val parent = issues.save(Issue(project = source, number = 1, title = "Pinned parent", pinnedAt = Instant.now()))
                val child = issues.save(Issue(project = source, number = 2, title = "Pinned child", parent = parent, pinnedAt = Instant.now()))
                issueService.moveIssue(parent.id!!, target.id!!, mover)
                issues.findById(parent.id!!).orElseThrow().pinnedAt shouldBe null
                issues.findById(child.id!!).orElseThrow().pinnedAt shouldBe null
                issues.updatePin(parent.id!!, source.id!!, Instant.now()) shouldBe 0
                issues.updatePin(parent.id!!, target.id!!, Instant.now()) shouldBe 1
                issueService.deleteIssueCascade(issues.findById(child.id!!).orElseThrow())
                issueService.deleteIssueCascade(issues.findById(parent.id!!).orElseThrow())
                issues.findById(parent.id!!).isPresent shouldBe false
            }
        }
    }
}
