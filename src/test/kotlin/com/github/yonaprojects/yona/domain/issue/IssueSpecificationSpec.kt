package com.github.yonaprojects.yona.domain.issue

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.domain.enumeration.State
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.annotation.Transactional

@Transactional
class IssueSpecificationSpec @Autowired constructor(
    private val issueRepository: IssueRepository,
    private val projectRepository: ProjectRepository,
    private val userRepository: UserRepository
) : AbstractIntegrationTest() {
    init {
        describe("Issue assignment filters") {
            it("matches either assignee once and excludes assigned issues from the unassigned filter") {
                val project = projectRepository.save(Project(name = "assignment-spec", owner = "spec-owner"))
                val first = userRepository.save(User(loginId = "spec-first", name = "First", email = "first@spec.test"))
                val second = userRepository.save(User(loginId = "spec-second", name = "Second", email = "second@spec.test"))
                val assigned = issueRepository.saveAndFlush(
                    Issue(project = project, number = 1L, title = "Assigned", state = State.OPEN, assignees = mutableSetOf(first, second))
                )
                val unassigned = issueRepository.saveAndFlush(
                    Issue(project = project, number = 2L, title = "Unassigned", state = State.OPEN)
                )
                val pageable = PageRequest.of(0, 1)
                fun filter(assigneeId: Long?) = IssueSpecification.filterIssues(
                    project, State.OPEN, null, null, assigneeId, null, null, null, null
                )

                for (user in listOf(first, second)) {
                    val page = issueRepository.findAll(filter(user.id), pageable)
                    page.content.map { it.id } shouldBe listOf(assigned.id)
                    page.totalElements shouldBe 1L
                    val organizationPage = issueRepository.findAll(
                        IssueSpecification.filterOrganizationIssues(listOf(project), State.OPEN, null, null, user.id, null),
                        pageable
                    )
                    organizationPage.content.map { it.id } shouldBe listOf(assigned.id)
                    organizationPage.totalElements shouldBe 1L
                }
                issueRepository.findAll(filter(-1L)).map { it.id } shouldBe listOf(unassigned.id)
                issueRepository.count(filter(null)) shouldBe 2L
            }
        }
    }
}
