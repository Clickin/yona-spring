package com.github.yonaprojects.yona.web

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.domain.issue.*
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectScope
import com.github.yonaprojects.yona.domain.user.User
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.domain.Specification
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import java.nio.file.Files
import java.time.Instant

@Transactional
@TestPropertySource(properties = ["yona.search.backend=lucene"])
class PinnedLuceneCompatibilitySpec @Autowired constructor(
    private val em: EntityManager,
    private val context: WebApplicationContext,
    private val index: IssueSearchIndex,
    private val tasks: IssueSearchTasks,
    private val search: IssueSearchService
) : AbstractIntegrationTest() {
    companion object {
        private val indexPath = Files.createTempDirectory("yona-pinned-search-")
        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("yona.search.index-dir") { indexPath.toString() }
            registry.add("yona.search.initial-delay-millis") { "3600000" }
        }
    }
    init {
        it("uses Lucene comment matches for pins and denies stale external plural assignees") {
            val owner = User(loginId = "combo-owner", name = "Owner", email = "combo-owner@example.test")
            val outsider = User(loginId = "combo-viewer", name = "Viewer", email = "combo-viewer@example.test")
            em.persist(owner)
            em.persist(outsider)
            val project = Project(owner = owner.loginId, name = "combo-search", projectScope = ProjectScope.PUBLIC)
            val secret = Project(owner = owner.loginId, name = "combo-secret", projectScope = ProjectScope.PRIVATE)
            em.persist(project)
            em.persist(secret)
            fun issue(number: Long, title: String, pinned: Boolean, target: Project = project): Issue = Issue(
                project = target, number = number, title = title, authorId = owner.id,
                pinnedAt = if (pinned) Instant.now() else null
            ).also { em.persist(it) }
            val match = issue(1, "Comment-only matching pin", true)
            issue(2, "Unrelated pin", true)
            val ordinary = issue(3, "needle normal result", false)
            val hidden = issue(1, "needle private external assignment", true, secret)
            hidden.assignees.add(outsider)
            em.persist(IssueComment(issue = match, authorId = owner.id, contents = "needle comment"))
            em.flush()
            index.synchronize(tasks::batch)
            val mvc = MockMvcBuilders.webAppContextSetup(context).build()
            for (order in listOf("relevance", "createdDate")) {
                val result = mvc.perform(get("/${project.owner}/${project.name}/issues")
                    .principal(UsernamePasswordAuthenticationToken(outsider.loginId, ""))
                    .param("filter", "needle").param("orderBy", order))
                    .andExpect(status().isOk).andReturn().modelAndView!!.model
                @Suppress("UNCHECKED_CAST")
                val pinned = result["pinnedIssues"] as List<Issue>
                pinned.map { it.id } shouldBe listOf(match.id)
                val page = result["issuePage"] as Page<*>
                page.content.map { (it as Issue).id } shouldBe listOf(ordinary.id)
                page.totalElements shouldBe 1L
            }
            val visible = search.search(Specification.unrestricted(), "needle", outsider, PageRequest.of(0, 20))
            visible.content.map { it.id }.toSet() shouldBe setOf(match.id, ordinary.id)
        }
    }
}
