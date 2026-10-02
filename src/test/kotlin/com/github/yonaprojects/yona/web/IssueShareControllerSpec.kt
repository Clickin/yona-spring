package com.github.yonaprojects.yona.web

import com.github.yonaprojects.yona.config.security.AccessControl
import com.github.yonaprojects.yona.domain.enumeration.Operation
import io.mockk.verify
import com.github.yonaprojects.yona.domain.issue.Issue
import com.github.yonaprojects.yona.domain.issue.IssueRepository
import com.github.yonaprojects.yona.domain.issue.IssueService
import com.github.yonaprojects.yona.domain.issue.IssueShareService
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.every
import io.mockk.mockk
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.*
import io.mockk.clearMocks

class IssueShareControllerSpec : DescribeSpec({
    val issueShareService = mockk<IssueShareService>()
    val projectRepository = mockk<ProjectRepository>()
    val issueRepository = mockk<IssueRepository>()
    val userRepository = mockk<UserRepository>()
    val issueService = mockk<IssueService>()
    val accessControl = mockk<AccessControl>()

    val controller = IssueShareController(
        issueShareService,
        projectRepository,
        issueRepository,
        userRepository,
        issueService,
        accessControl
    )
    val mockMvc = MockMvcBuilders.standaloneSetup(controller).build()

    beforeTest {
        clearMocks(
            issueShareService,
            projectRepository,
            issueRepository,
            userRepository,
            issueService,
            accessControl
        )
        every { accessControl.isAllowed(any(), any(), any<Issue>(), Operation.UPDATE) } returns true
    }

    describe("IssueShareController 단위 테스트") {
        val user = User(id = 1L, loginId = "testuser", name = "테스트유저", email = "test@example.com")
        val auth = UsernamePasswordAuthenticationToken("testuser", "password")
        val project = Project(id = 10L, name = "testproject", owner = "testowner")
        val issue = Issue(id = 100L, title = "testissue", project = project, number = 1L)

        describe("GET /-_-api/v1/owners/{owner}/projects/{projectName}/assignableUsers") {
            it("프로젝트 내 담당자 지정 가능한 유저 목록을 반환해야 한다") {
                every { userRepository.findByLoginId("testuser") } returns Optional.of(user)
                every { projectRepository.findByOwnerAndNameOrPreviousPlace("testowner", "testproject") } returns Optional.of(project)
                
                val resultList = listOf(
                    mapOf("loginId" to "testuser", "name" to "나에게 지정", "type" to "user")
                )
                every { issueShareService.findAssignableUsersOfProject(project, "", user) } returns resultList

                mockMvc.perform(
                    get("/-_-api/v1/owners/testowner/projects/testproject/assignableUsers")
                        .principal(auth)
                )
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$[0].loginId").value("testuser"))
                    .andExpect(jsonPath("$[0].name").value("나에게 지정"))
            }

            it("인증되지 않은 요청은 401을 반환해야 한다") {
                mockMvc.perform(get("/-_-api/v1/owners/testowner/projects/testproject/assignableUsers"))
                    .andExpect(status().isUnauthorized)
            }

            it("존재하지 않는 프로젝트면 404를 반환해야 한다") {
                every { userRepository.findByLoginId("testuser") } returns Optional.of(user)
                every { projectRepository.findByOwnerAndNameOrPreviousPlace("testowner", "nosuch") } returns Optional.empty()

                mockMvc.perform(
                    get("/-_-api/v1/owners/testowner/projects/nosuch/assignableUsers").principal(auth)
                ).andExpect(status().isNotFound)
            }
        }

        describe("GET /-_-api/v1/owners/{owner}/projects/{projectName}/issues/{number}/assignableUsers") {
            it("이슈에 담당자 지정 가능한 유저 목록을 반환해야 한다") {
                every { userRepository.findByLoginId("testuser") } returns Optional.of(user)
                every { projectRepository.findByOwnerAndNameOrPreviousPlace("testowner", "testproject") } returns Optional.of(project)
                every { issueRepository.findByProjectAndNumber(project, 1L) } returns issue
                every { issueShareService.findAssignableUsers(issue, "", user) } returns listOf(mapOf("loginId" to "testuser"))

                mockMvc.perform(
                    get("/-_-api/v1/owners/testowner/projects/testproject/issues/1/assignableUsers").principal(auth)
                )
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$[0].loginId").value("testuser"))
            }

            it("인증되지 않은 요청은 401을 반환해야 한다") {
                mockMvc.perform(get("/-_-api/v1/owners/testowner/projects/testproject/issues/1/assignableUsers"))
                    .andExpect(status().isUnauthorized)
            }

            it("존재하지 않는 프로젝트면 404를 반환해야 한다") {
                every { userRepository.findByLoginId("testuser") } returns Optional.of(user)
                every { projectRepository.findByOwnerAndNameOrPreviousPlace("testowner", "nosuch") } returns Optional.empty()

                mockMvc.perform(
                    get("/-_-api/v1/owners/testowner/projects/nosuch/issues/1/assignableUsers").principal(auth)
                ).andExpect(status().isNotFound)
            }

            it("존재하지 않는 이슈면 404를 반환해야 한다") {
                every { userRepository.findByLoginId("testuser") } returns Optional.of(user)
                every { projectRepository.findByOwnerAndNameOrPreviousPlace("testowner", "testproject") } returns Optional.of(project)
                every { issueRepository.findByProjectAndNumber(project, 999L) } returns null

                mockMvc.perform(
                    get("/-_-api/v1/owners/testowner/projects/testproject/issues/999/assignableUsers").principal(auth)
                ).andExpect(status().isNotFound)
            }
        }

        describe("POST /-_-api/v1/owners/{owner}/projects/{projectName}/issues/{number}/assignees") {
            it("성공적으로 담당자를 변경하고 결과를 반환해야 한다") {
                val targetUser = User(id = 2L, loginId = "assigneeUser", name = "담당자", email = "assignee@example.com")

                every { userRepository.findByLoginId("testuser") } returns Optional.of(user)
                every { projectRepository.findByOwnerAndNameOrPreviousPlace("testowner", "testproject") } returns Optional.of(project)
                every { issueRepository.findByProjectAndNumber(project, 1L) } returns issue
                every { userRepository.findByLoginId("assigneeUser") } returns Optional.of(targetUser)

                val updatedIssue = Issue(id = 100L, title = "testissue", project = project, number = 1L)
                updatedIssue.assignees.add(targetUser)
                every { issueService.changeAssignees(100L, listOf(targetUser), "testuser") } returns updatedIssue

                val requestBody = """
                    {
                        "assignees": ["assigneeUser"]
                    }
                """.trimIndent()

                mockMvc.perform(
                    post("/-_-api/v1/owners/testowner/projects/testproject/issues/1/assignees")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                )
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.assignees[0].loginId").value("assigneeUser"))
                    .andExpect(jsonPath("$.assignees[0].name").value("담당자"))
            }

            it("인증되지 않은 요청은 401을 반환해야 한다") {
                mockMvc.perform(
                    post("/-_-api/v1/owners/testowner/projects/testproject/issues/1/assignees")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"assignees": ["a"]}""")
                ).andExpect(status().isUnauthorized)
            }

            it("존재하지 않는 프로젝트면 404를 반환해야 한다") {
                every { userRepository.findByLoginId("testuser") } returns Optional.of(user)
                every { projectRepository.findByOwnerAndNameOrPreviousPlace("testowner", "nosuch") } returns Optional.empty()

                mockMvc.perform(
                    post("/-_-api/v1/owners/testowner/projects/nosuch/issues/1/assignees")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"assignees": ["a"]}""")
                ).andExpect(status().isNotFound)
            }

            it("존재하지 않는 이슈면 404를 반환해야 한다") {
                every { userRepository.findByLoginId("testuser") } returns Optional.of(user)
                every { projectRepository.findByOwnerAndNameOrPreviousPlace("testowner", "testproject") } returns Optional.of(project)
                every { issueRepository.findByProjectAndNumber(project, 999L) } returns null

                mockMvc.perform(
                    post("/-_-api/v1/owners/testowner/projects/testproject/issues/999/assignees")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"assignees": ["a"]}""")
                ).andExpect(status().isNotFound)
            }

            it("assignees 필드가 리스트가 아니면 400을 반환해야 한다") {
                every { userRepository.findByLoginId("testuser") } returns Optional.of(user)
                every { projectRepository.findByOwnerAndNameOrPreviousPlace("testowner", "testproject") } returns Optional.of(project)
                every { issueRepository.findByProjectAndNumber(project, 1L) } returns issue

                mockMvc.perform(
                    post("/-_-api/v1/owners/testowner/projects/testproject/issues/1/assignees")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"assignees": "notalist"}""")
                ).andExpect(status().isBadRequest)
            }

            it("빈 배열로 전체 담당자를 해제한다") {
                every { userRepository.findByLoginId("testuser") } returns Optional.of(user)
                every { projectRepository.findByOwnerAndNameOrPreviousPlace("testowner", "testproject") } returns Optional.of(project)
                every { issueRepository.findByProjectAndNumber(project, 1L) } returns issue
                every { issueService.changeAssignees(100L, emptyList(), "testuser") } returns issue

                mockMvc.perform(
                    post("/-_-api/v1/owners/testowner/projects/testproject/issues/1/assignees")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"assignees": []}""")
                )
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.assignees").isEmpty)
            }

            it("존재하지 않는 로그인ID는 변경 없이 400을 반환한다") {
                every { userRepository.findByLoginId("testuser") } returns Optional.of(user)
                every { projectRepository.findByOwnerAndNameOrPreviousPlace("testowner", "testproject") } returns Optional.of(project)
                every { issueRepository.findByProjectAndNumber(project, 1L) } returns issue
                every { userRepository.findByLoginId("ghost") } returns Optional.empty()

                mockMvc.perform(
                    post("/-_-api/v1/owners/testowner/projects/testproject/issues/1/assignees")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"assignees": ["ghost"]}""")
                )
                    .andExpect(status().isBadRequest)
                verify(exactly = 0) { issueService.changeAssignees(any(), any(), any()) }
            }

            it("개별 담당자 토글은 다른 담당자를 보존하고 전체 해제를 지원한다") {
                val first = User(id = 2L, loginId = "first", name = "First")
                val second = User(id = 3L, loginId = "second", name = "Second")
                val assignedIssue = Issue(id = 100L, title = "testissue", project = project, number = 1L)
                assignedIssue.assignees.add(first)
                every { userRepository.findByLoginId("testuser") } returns Optional.of(user)
                every { userRepository.findByLoginId("second") } returns Optional.of(second)
                every { userRepository.findByLoginId("first") } returns Optional.of(first)
                every { projectRepository.findByOwnerAndNameOrPreviousPlace("testowner", "testproject") } returns Optional.of(project)
                every { issueRepository.findByProjectAndNumber(project, 1L) } returns assignedIssue
                every { issueService.changeAssignees(100L, any(), "testuser") } answers {
                    assignedIssue.assignees = secondArg<List<User>>().toMutableSet()
                    assignedIssue
                }
                mockMvc.perform(post("/-_-api/v1/owners/testowner/projects/testproject/issues/1/assignees")
                    .principal(auth).contentType(MediaType.APPLICATION_JSON)
                    .content("""{"assignees":["second"],"action":"toggle"}"""))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.assignees[0].loginId").value("first"))
                    .andExpect(jsonPath("$.assignees[1].loginId").value("second"))
                mockMvc.perform(post("/-_-api/v1/owners/testowner/projects/testproject/issues/1/assignees")
                    .principal(auth).contentType(MediaType.APPLICATION_JSON)
                    .content("""{"assignees":["first"],"action":"toggle"}"""))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.assignees[0].loginId").value("second"))
                    .andExpect(jsonPath("$.assignees[1]").doesNotExist())
                mockMvc.perform(post("/-_-api/v1/owners/testowner/projects/testproject/issues/1/assignees")
                    .principal(auth).contentType(MediaType.APPLICATION_JSON)
                    .content("""{"assignees":[],"action":"clear"}"""))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.assignees").isEmpty)
            }

            it("권한 없는 담당자 변경은 403을 반환한다") {
                every { userRepository.findByLoginId("testuser") } returns Optional.of(user)
                every { projectRepository.findByOwnerAndNameOrPreviousPlace("testowner", "testproject") } returns Optional.of(project)
                every { issueRepository.findByProjectAndNumber(project, 1L) } returns issue
                every { accessControl.isAllowed(user, project, issue, Operation.UPDATE) } returns false
                mockMvc.perform(post("/-_-api/v1/owners/testowner/projects/testproject/issues/1/assignees")
                    .principal(auth).contentType(MediaType.APPLICATION_JSON)
                    .content("""{"assignees":[],"action":"clear"}"""))
                    .andExpect(status().isForbidden)
                verify(exactly = 0) { issueService.changeAssignees(any(), any(), any()) }
            }
        }

        describe("GET /-_-api/v1/owners/{owner}/projects/{projectName}/issues/{number}/findSharer") {
            it("검색어로 공유 가능한 사용자를 찾아 반환해야 한다") {
                every { projectRepository.findByOwnerAndNameOrPreviousPlace("testowner", "testproject") } returns Optional.of(project)
                every { issueRepository.findByProjectAndNumber(project, 1L) } returns issue
                every { issueShareService.findSharerByloginIds(issue, "que") } returns listOf(mapOf("loginId" to "found"))

                mockMvc.perform(
                    get("/-_-api/v1/owners/testowner/projects/testproject/issues/1/findSharer").param("query", "que")
                )
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$[0].loginId").value("found"))
            }

            it("존재하지 않는 프로젝트면 404를 반환해야 한다") {
                every { projectRepository.findByOwnerAndNameOrPreviousPlace("testowner", "nosuch") } returns Optional.empty()

                mockMvc.perform(
                    get("/-_-api/v1/owners/testowner/projects/nosuch/issues/1/findSharer").param("query", "q")
                ).andExpect(status().isNotFound)
            }

            it("존재하지 않는 이슈면 404를 반환해야 한다") {
                every { projectRepository.findByOwnerAndNameOrPreviousPlace("testowner", "testproject") } returns Optional.of(project)
                every { issueRepository.findByProjectAndNumber(project, 999L) } returns null

                mockMvc.perform(
                    get("/-_-api/v1/owners/testowner/projects/testproject/issues/999/findSharer").param("query", "q")
                ).andExpect(status().isNotFound)
            }
        }

        describe("GET /-_-api/v1/owners/{owner}/projects/{projectName}/issues/{number}/sharableUsers") {
            it("공유 가능한 사용자 목록을 반환해야 한다") {
                every { issueShareService.findSharableUsers("que", null) } returns listOf(mapOf("loginId" to "sharable1"))

                mockMvc.perform(
                    get("/-_-api/v1/owners/testowner/projects/testproject/issues/1/sharableUsers").param("query", "que")
                )
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$[0].loginId").value("sharable1"))
            }
        }

        describe("POST /-_-api/v1/owners/{owner}/projects/{projectName}/issues/{number}/share") {
            it("공유자를 추가하고 결과를 반환해야 한다") {
                every { userRepository.findByLoginId("testuser") } returns Optional.of(user)
                every { projectRepository.findByOwnerAndNameOrPreviousPlace("testowner", "testproject") } returns Optional.of(project)
                every { issueRepository.findByProjectAndNumber(project, 1L) } returns issue

                val mockResult = mapOf("action" to "added", "sharer" to "공유대상자")
                every { issueShareService.changeSharer(issue, "sharerLoginId", "user", "add", user) } returns mockResult

                val requestBody = """
                    {
                        "sharer": {
                            "loginId": "sharerLoginId",
                            "type": "user"
                        },
                        "action": "add"
                    }
                """.trimIndent()

                mockMvc.perform(
                    post("/-_-api/v1/owners/testowner/projects/testproject/issues/1/share")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                )
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.action").value("added"))
                    .andExpect(jsonPath("$.sharer").value("공유대상자"))
            }

            it("type/action이 생략되면 기본값 user/add로 처리되어야 한다") {
                every { userRepository.findByLoginId("testuser") } returns Optional.of(user)
                every { projectRepository.findByOwnerAndNameOrPreviousPlace("testowner", "testproject") } returns Optional.of(project)
                every { issueRepository.findByProjectAndNumber(project, 1L) } returns issue
                every { issueShareService.changeSharer(issue, "sharerLoginId", "user", "add", user) } returns mapOf("action" to "added")

                mockMvc.perform(
                    post("/-_-api/v1/owners/testowner/projects/testproject/issues/1/share")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"sharer": {"loginId": "sharerLoginId"}}""")
                ).andExpect(status().isOk)
            }

            it("인증되지 않은 요청은 401을 반환해야 한다") {
                mockMvc.perform(
                    post("/-_-api/v1/owners/testowner/projects/testproject/issues/1/share")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"sharer": {"loginId": "a"}}""")
                ).andExpect(status().isUnauthorized)
            }

            it("존재하지 않는 프로젝트면 404를 반환해야 한다") {
                every { userRepository.findByLoginId("testuser") } returns Optional.of(user)
                every { projectRepository.findByOwnerAndNameOrPreviousPlace("testowner", "nosuch") } returns Optional.empty()

                mockMvc.perform(
                    post("/-_-api/v1/owners/testowner/projects/nosuch/issues/1/share")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"sharer": {"loginId": "a"}}""")
                ).andExpect(status().isNotFound)
            }

            it("존재하지 않는 이슈면 404를 반환해야 한다") {
                every { userRepository.findByLoginId("testuser") } returns Optional.of(user)
                every { projectRepository.findByOwnerAndNameOrPreviousPlace("testowner", "testproject") } returns Optional.of(project)
                every { issueRepository.findByProjectAndNumber(project, 999L) } returns null

                mockMvc.perform(
                    post("/-_-api/v1/owners/testowner/projects/testproject/issues/999/share")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"sharer": {"loginId": "a"}}""")
                ).andExpect(status().isNotFound)
            }

            it("sharer 필드가 맵이 아니면 400을 반환해야 한다") {
                every { userRepository.findByLoginId("testuser") } returns Optional.of(user)
                every { projectRepository.findByOwnerAndNameOrPreviousPlace("testowner", "testproject") } returns Optional.of(project)
                every { issueRepository.findByProjectAndNumber(project, 1L) } returns issue

                mockMvc.perform(
                    post("/-_-api/v1/owners/testowner/projects/testproject/issues/1/share")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"sharer": "notamap"}""")
                ).andExpect(status().isBadRequest)
            }

            it("sharer.loginId가 없으면 400을 반환해야 한다") {
                every { userRepository.findByLoginId("testuser") } returns Optional.of(user)
                every { projectRepository.findByOwnerAndNameOrPreviousPlace("testowner", "testproject") } returns Optional.of(project)
                every { issueRepository.findByProjectAndNumber(project, 1L) } returns issue

                mockMvc.perform(
                    post("/-_-api/v1/owners/testowner/projects/testproject/issues/1/share")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"sharer": {}}""")
                ).andExpect(status().isBadRequest)
            }

            it("sharer.loginId가 JSON null이면 400을 반환해야 한다") {
                every { userRepository.findByLoginId("testuser") } returns Optional.of(user)
                every { projectRepository.findByOwnerAndNameOrPreviousPlace("testowner", "testproject") } returns Optional.of(project)
                every { issueRepository.findByProjectAndNumber(project, 1L) } returns issue

                mockMvc.perform(
                    post("/-_-api/v1/owners/testowner/projects/testproject/issues/1/share")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"sharer": {"loginId": null, "type": null}, "action": null}""")
                ).andExpect(status().isBadRequest)
            }

            it("type/action이 JSON null로 명시돼도 기본값 user/add로 처리되어야 한다") {
                every { userRepository.findByLoginId("testuser") } returns Optional.of(user)
                every { projectRepository.findByOwnerAndNameOrPreviousPlace("testowner", "testproject") } returns Optional.of(project)
                every { issueRepository.findByProjectAndNumber(project, 1L) } returns issue
                every { issueShareService.changeSharer(issue, "sharerLoginId2", "user", "add", user) } returns mapOf("action" to "added")

                mockMvc.perform(
                    post("/-_-api/v1/owners/testowner/projects/testproject/issues/1/share")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"sharer": {"loginId": "sharerLoginId2", "type": null}, "action": null}""")
                ).andExpect(status().isOk)
            }
        }
    }
})
