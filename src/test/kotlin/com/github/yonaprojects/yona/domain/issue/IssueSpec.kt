package com.github.yonaprojects.yona.domain.issue

import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.user.User
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class IssueSpec : DescribeSpec({
    describe("assignment membership") {
        it("recognizes every assigned user by persisted identity and never anonymous users") {
            val issue = Issue(
                project = Project(name = "project", owner = "owner"),
                assignees = mutableSetOf(User(id = 1L), User(id = 2L))
            )
            issue.hasAssignee(1L) shouldBe true
            issue.hasAssignee(2L) shouldBe true
            issue.hasAssignee(3L) shouldBe false
            issue.hasAssignee(null) shouldBe false
            issue.assignees.removeIf { it.id == 1L }
            issue.hasAssignee(1L) shouldBe false
            issue.hasAssignee(2L) shouldBe true
        }
    }
})
