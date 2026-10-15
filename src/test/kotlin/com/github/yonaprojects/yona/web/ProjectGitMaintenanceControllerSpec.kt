package com.github.yonaprojects.yona.web

import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.project.ProjectUser
import com.github.yonaprojects.yona.domain.project.ProjectUserRepository
import com.github.yonaprojects.yona.domain.role.Role
import com.github.yonaprojects.yona.domain.role.RoleType
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import com.github.yonaprojects.yona.domain.vcs.GitMaintenanceService
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.Optional

class ProjectGitMaintenanceControllerSpec : DescribeSpec({
    val projects = mockk<ProjectRepository>()
    val members = mockk<ProjectUserRepository>()
    val users = mockk<UserRepository>()
    val maintenance = mockk<GitMaintenanceService>()
    val mvc = MockMvcBuilders.standaloneSetup(
        ProjectGitMaintenanceController(projects, members, users, maintenance)
    ).build()
    val project = Project(id = 91, owner = "owner", name = "repo", vcs = "GIT")
    val member = User(id = 92, loginId = "member", name = "Member", email = "member@example.test")
    val authentication = UsernamePasswordAuthenticationToken("member", "unused")

    beforeTest {
        every { users.findByLoginId("member") } returns Optional.of(member)
        every { projects.findByOwnerAndNameOrPreviousPlace("owner", "repo") } returns Optional.of(project)
        every { members.findByProjectIdAndUserId(91, 92) } returns Optional.of(
            ProjectUser(user = member, project = project, role = Role(id = RoleType.MEMBER.roleType))
        )
    }

    it("members cannot request GC or read its management status") {
        mvc.perform(post("/owner/repo/setting/git-maintenance").principal(authentication))
            .andExpect(status().isForbidden)
        mvc.perform(get("/owner/repo/setting/git-maintenance").principal(authentication))
            .andExpect(status().isForbidden)
        verify(exactly = 0) { maintenance.enqueue(any(), any()) }
        verify(exactly = 0) { maintenance.status(any()) }
    }

    it("anonymous requests cannot enqueue GC") {
        mvc.perform(post("/owner/repo/setting/git-maintenance"))
            .andExpect(status().isUnauthorized)
        verify(exactly = 0) { maintenance.enqueue(any(), any()) }
    }
})
