package com.github.yonaprojects.yona.domain.issue

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.site.SiteService
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.project.ProjectScope
import com.github.yonaprojects.yona.domain.user.UserRepository
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManager
import org.jsoup.Jsoup
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.time.Instant

@Transactional
class SavedIssueViewRepositorySpec @Autowired constructor(
    private val views: SavedIssueViewRepository,
    private val projects: ProjectRepository,
    private val users: UserRepository,
    private val siteService: SiteService,
    private val entityManager: EntityManager,
    private val issues: IssueRepository,
    private val context: WebApplicationContext,
    private val objectMapper: ObjectMapper
) : AbstractIntegrationTest() {
    init {
        it("opens saved relevance views as rendered database lists in newest-first order without changing the saved sort") {
            val owner = users.save(User(loginId = "saved-relevance-owner", name = "Owner", email = "saved-relevance@example.test"))
            val project = projects.save(Project(name = "saved-relevance", owner = owner.loginId, projectScope = ProjectScope.PUBLIC))
            val newest = issues.save(Issue(title = "Needle newest", project = project, number = 1,
                authorId = owner.id, authorLoginId = owner.loginId, authorName = owner.name,
                createdDate = Instant.parse("2026-10-03T00:00:00Z")))
            val oldest = issues.save(Issue(title = "Needle oldest", project = project, number = 2,
                authorId = owner.id, authorLoginId = owner.loginId, authorName = owner.name,
                createdDate = Instant.parse("2026-10-01T00:00:00Z")))
            val middle = issues.save(Issue(title = "Needle middle", project = project, number = 3,
                authorId = owner.id, authorLoginId = owner.loginId, authorName = owner.name,
                createdDate = Instant.parse("2026-10-02T00:00:00Z")))
            entityManager.flush()
            entityManager.clear()
            val mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity()).build()
            val api = "/api/v1/projects/${project.owner}/${project.name}/issues/saved-views"
            listOf("", "Needle").forEach { filter ->
                val parameters = mapOf("orderBy" to listOf("relevance"), "orderDir" to listOf("asc"), "filter" to listOf(filter))
                val created = mvc.perform(post(api).with(user(owner.loginId).roles("ACTIVE")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("name" to "Relevance", "visibility" to "PERSONAL", "parameters" to parameters))))
                    .andExpect(status().isCreated).andReturn().response
                val id = objectMapper.readTree(created.contentAsString).get("id").asLong()
                val url = mvc.perform(get("$api/$id/open").with(user(owner.loginId).roles("ACTIVE")))
                    .andExpect(status().isFound).andReturn().response.redirectedUrl!!
                IssueViewQuery.decode(url.substringAfter('?')).getValue("orderBy") shouldBe listOf("relevance")
                IssueViewQuery.decode(views.findById(id).get().queryParameters).getValue("orderBy") shouldBe listOf("relevance")
                val rendered = mvc.perform(get(URI.create(url)).with(user(owner.loginId).roles("ACTIVE")))
                    .andExpect(status().isOk).andReturn()
                rendered.modelAndView!!.model["orderBy"] shouldBe "relevance"
                Jsoup.parse(rendered.response.contentAsString).select("#issue-list li.post-item").map { it.id() } shouldBe
                    listOf(newest, middle, oldest).map { "issue-item-${it.id}" }
            }
        }

        it("isolates personal views and cleans them on user deletion, retaining shared views until project deletion") {
            val alice = users.save(User(loginId = "saved-view-alice", name = "Alice", email = "saved-view-alice@example.test"))
            val bob = users.save(User(loginId = "saved-view-bob", name = "Bob", email = "saved-view-bob@example.test"))
            val project = projects.save(Project(name = "saved-view-project", owner = "saved-view-owner"))
            val other = projects.save(Project(name = "saved-view-other", owner = "saved-view-owner"))
            val personal = views.save(SavedIssueView(project = project, owner = alice, name = "Private", queryParameters = "state=closed"))
            val shared = views.save(SavedIssueView(project = project, name = "Shared", queryParameters = "orderBy=dueDate"))
            val otherShared = views.save(SavedIssueView(project = other, name = "Other project"))
            entityManager.flush()
            entityManager.clear()

            views.findVisible(project.id!!, alice.id!!).map { it.name } shouldBe listOf("Private", "Shared")
            views.findVisible(project.id!!, bob.id!!).map { it.name } shouldBe listOf("Shared")
            views.findByIdAndProjectId(otherShared.id!!, project.id!!) shouldBe null

            siteService.deleteUser(alice.id!!)
            entityManager.flush()
            entityManager.clear()
            views.existsById(personal.id!!) shouldBe false
            views.findById(shared.id!!).get().queryParameters shouldBe "orderBy=dueDate"

            projects.deleteById(project.id!!)
            entityManager.flush()
            entityManager.clear()
            views.existsById(shared.id!!) shouldBe false
            views.existsById(otherShared.id!!) shouldBe true
        }
    }
}
