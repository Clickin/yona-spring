package com.github.yonaprojects.yona.domain.issue

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.site.SiteService
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional

@Transactional
class SavedIssueViewRepositorySpec @Autowired constructor(
    private val views: SavedIssueViewRepository,
    private val projects: ProjectRepository,
    private val users: UserRepository,
    private val siteService: SiteService,
    private val entityManager: EntityManager
) : AbstractIntegrationTest() {
    init {
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
