package com.github.yonaprojects.yona.domain.support

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.domain.issue.Issue
import com.github.yonaprojects.yona.domain.issue.IssueRepository
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional

@Transactional
class StatisticsServiceImplSpec @Autowired constructor(
    private val statisticsService: StatisticsService,
    private val issueRepository: IssueRepository,
    private val projectRepository: ProjectRepository,
    private val userRepository: UserRepository
) : AbstractIntegrationTest() {
    init {
        describe("User assignment statistics") {
            it("counts a shared assignment once for each user without counting unassigned issues") {
                val first = userRepository.save(User(loginId = "stats-first", name = "First", email = "first@stats.test"))
                val second = userRepository.save(User(loginId = "stats-second", name = "Second", email = "second@stats.test"))
                val project = projectRepository.save(Project(name = "stats-assignees", owner = "stats-owner"))
                issueRepository.saveAndFlush(Issue(project = project, number = 1L, title = "Shared", assignees = mutableSetOf(first, second)))
                issueRepository.saveAndFlush(Issue(project = project, number = 2L, title = "First only", assignees = mutableSetOf(first)))
                issueRepository.saveAndFlush(Issue(project = project, number = 3L, title = "Unassigned", authorId = second.id))

                statisticsService.getUserStatistics(first.id!!).assignedIssue shouldBe 2L
                val secondStats = statisticsService.getUserStatistics(second.id!!)
                secondStats.assignedIssue shouldBe 1L
                secondStats.issue shouldBe 1L
            }
        }
    }
}
