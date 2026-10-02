package com.github.yonaprojects.yona.web

import com.github.yonaprojects.yona.config.security.AccessControl
import com.github.yonaprojects.yona.domain.enumeration.Operation
import com.github.yonaprojects.yona.domain.issue.*
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.Optional

class SavedIssueViewControllerSpec : DescribeSpec({
    val repository = mockk<SavedIssueViewRepository>()
    val projects = mockk<ProjectRepository>()
    val users = mockk<UserRepository>()
    val access = mockk<AccessControl>()
    val service = SavedIssueViewService(repository, access)
    val mvc = MockMvcBuilders.standaloneSetup(SavedIssueViewController(service, projects, users)).build()
    val project = Project(id = 1, owner = "team", name = "repo")
    val alice = User(id = 10, loginId = "alice")
    val bob = User(id = 11, loginId = "bob")
    val aliceAuth = UsernamePasswordAuthenticationToken("alice", "unused")
    val bobAuth = UsernamePasswordAuthenticationToken("bob", "unused")
    val views = linkedMapOf<Long, SavedIssueView>()
    val api = "/api/v1/projects/team/repo/issues/saved-views"

    beforeTest {
        views.clear()
        every { projects.findByOwnerAndNameOrPreviousPlace("team", "repo") } returns Optional.of(project)
        every { users.findByLoginId("alice") } returns Optional.of(alice)
        every { users.findByLoginId("bob") } returns Optional.of(bob)
        every { access.isAllowedToReadProject(any(), project) } returns true
        every { access.isAllowed(alice, project, Operation.UPDATE) } returns true
        every { access.isAllowed(bob, project, Operation.UPDATE) } returns false
        every { repository.save(any()) } answers {
            firstArg<SavedIssueView>().also { if (it.id == null) it.id = (views.size + 1).toLong(); views[it.id!!] = it }
        }
        every { repository.findByIdAndProjectId(any(), 1) } answers { views[firstArg()] }
        every { repository.findVisible(1, any()) } answers {
            views.values.filter { it.owner == null || it.owner!!.id == secondArg<Long>() }
        }
        every { repository.delete(any()) } answers { views.remove(firstArg<SavedIssueView>().id); Unit }
    }

    it("round trips search values without interpreting URLs, repeated labels or sort as navigation") {
        val params = mapOf("state" to listOf("closed"), "filter" to listOf("//evil.example/?x=1&state=open + 한글"),
            "assigneeId" to listOf("-1"), "milestoneId" to listOf("4"), "authorId" to listOf("10"),
            "commenterId" to listOf("11"), "labelIds" to listOf("2", "3"), "dueDate" to listOf("2026-10-02"),
            "orderBy" to listOf("updatedDate"), "orderDir" to listOf("asc"), "itemsPerPage" to listOf("30"))
        val created = service.create(project, alice, "My work", SavedIssueViewService.Visibility.PERSONAL, params)
        mvc.perform(get("$api/1/open").principal(aliceAuth))
            .andExpect(status().is3xxRedirection).andExpect(redirectedUrl(created.url))
        IssueViewQuery.decode(created.url.substringAfter('?')) shouldBe params
        created.url.substringBefore('?') shouldBe "/team/repo/issues"
        mvc.perform(patch("$api/1").principal(aliceAuth).contentType(MediaType.APPLICATION_JSON).content("""{"name":"Renamed"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.name").value("Renamed"))
        mvc.perform(delete("$api/1").principal(aliceAuth)).andExpect(status().isNoContent)
        mvc.perform(get("$api/1").principal(aliceAuth)).andExpect(status().isNotFound)
    }

    it("keeps personal views private even from another project manager") {
        service.create(project, alice, "Private", SavedIssueViewService.Visibility.PERSONAL, emptyMap())
        every { access.isAllowed(bob, project, Operation.UPDATE) } returns true
        mvc.perform(get(api).principal(bobAuth)).andExpect(jsonPath("$").isEmpty)
        mvc.perform(get("$api/1/open").principal(bobAuth)).andExpect(status().isNotFound)
        mvc.perform(patch("$api/1").principal(bobAuth).contentType(MediaType.APPLICATION_JSON).content("""{"name":"Stolen"}"""))
            .andExpect(status().isNotFound)
        mvc.perform(delete("$api/1").principal(bobAuth)).andExpect(status().isNotFound)
    }

    it("allows shared reads but only project managers can create rename or delete shared views") {
        mvc.perform(post(api).principal(aliceAuth).contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":"Shared","visibility":"PROJECT","parameters":{"orderBy":["dueDate"]}}"""))
            .andExpect(status().isCreated)
        mvc.perform(get(api).principal(bobAuth)).andExpect(jsonPath("$[0].name").value("Shared"))
        mvc.perform(post(api).principal(bobAuth).contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":"No","visibility":"PROJECT"}"""))
            .andExpect(status().isForbidden)
        mvc.perform(patch("$api/1").principal(bobAuth).contentType(MediaType.APPLICATION_JSON).content("""{"name":"No"}"""))
            .andExpect(status().isForbidden)
        mvc.perform(delete("$api/1").principal(bobAuth)).andExpect(status().isForbidden)
        every { access.isAllowedToReadProject(bob, project) } returns false
        mvc.perform(get(api).principal(bobAuth)).andExpect(status().isForbidden)
        mvc.perform(get("$api/1/open").principal(bobAuth)).andExpect(status().isForbidden)
        mvc.perform(get(api)).andExpect(status().isUnauthorized)
    }

    it("rejects redirect keys, export state, arbitrary sorts and invalid bounds before saving") {
        listOf(""""url":["https://evil.example"]""", """"format":["xls"]""",
            """"selected":["42"]""", """"orderBy":["project.owner"]""",
            """"itemsPerPage":["0"]""", """"state":["open","closed"]""",
            """"dueDate":["2026-99-99"]""").forEach { parameter ->
            mvc.perform(post(api).principal(aliceAuth).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Bad","visibility":"PERSONAL","parameters":{$parameter}}"""))
                .andExpect(status().isBadRequest)
        }
        views.isEmpty() shouldBe true
    }

    it("cannot use another project's view ID or bypass permissions through web forms") {
        service.create(project, alice, "Shared", SavedIssueViewService.Visibility.PROJECT, emptyMap())
        mvc.perform(post("/team/repo/issues/saved-views/1/delete").principal(bobAuth)).andExpect(status().isForbidden)
        mvc.perform(post("/team/repo/issues/saved-views").principal(aliceAuth)
            .param("name", "Bad").param("visibility", "PERSONAL").param("redirect", "//evil.example"))
            .andExpect(status().isBadRequest)
        val otherProject = Project(id = 2, owner = "team", name = "other")
        every { projects.findByOwnerAndNameOrPreviousPlace("team", "other") } returns Optional.of(otherProject)
        every { access.isAllowedToReadProject(alice, otherProject) } returns true
        every { repository.findByIdAndProjectId(1, 2) } returns null
        mvc.perform(get("/api/v1/projects/team/other/issues/saved-views/1").principal(aliceAuth)).andExpect(status().isNotFound)
    }
})
